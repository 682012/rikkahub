package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 产出格式 / 质量 / 尺寸 / 导出这些图像选项的换算。
 *
 * 用例里的模型名和取值都来自真实接口文档与实测记录：请求 png 拿回过 JPEG、请求 1024x1024 拿回过 1254x1254，
 * 所以这里的断言只说「请求里下发什么」，不声称「产出一定是什么」。
 */
class MediaImageParamsTest {
    private val openAIImage = MediaGenerationProviderSetting.OpenAI().capabilities(MediaKind.IMAGE)!!

    @Test
    fun `format quality and complexity are sent as extra parameters`() {
        val dispatch = MediaImageOptions(
            format = MediaImageFormat.JPEG,
            quality = MediaImageQuality.HIGH,
            complexity = MediaImageComplexity.HIGH,
        ).dispatchFor("gpt-image-2.6-sunburst", MediaCreationParams(resolution = "1536x1024"))

        assertEquals("1536x1024", dispatch.resolution)
        assertEquals(JsonPrimitive("jpeg"), dispatch.extra["output_format"])
        assertEquals(JsonPrimitive("high"), dispatch.extra["quality"])
        assertEquals(JsonPrimitive(100), dispatch.extra["output_compression"])
        assertEquals(JsonPrimitive("high"), dispatch.extra["complexity"])
        assertTrue(dispatch.notes.isEmpty())
    }

    @Test
    fun `png gets no compression parameter`() {
        val dispatch = MediaImageOptions(format = MediaImageFormat.PNG, quality = MediaImageQuality.MEDIUM)
            .dispatchFor("gpt-image-2.5-flare", MediaCreationParams())

        assertNull(dispatch.extra["output_compression"])
        assertEquals(JsonPrimitive("png"), dispatch.extra["output_format"])
        assertEquals(JsonPrimitive("medium"), dispatch.extra["quality"])
    }

    @Test
    fun `auto quality is not sent`() {
        val dispatch = MediaImageOptions(quality = MediaImageQuality.AUTO)
            .dispatchFor("gpt-image-2.6-flare", MediaCreationParams())

        assertNull(dispatch.extra["quality"])
    }

    @Test
    fun `unverified extended quality stays at verified high`() {
        val dispatch = MediaImageOptions(quality = MediaImageQuality.MAX).dispatchFor("gpt-image-2.6", MediaCreationParams())
        assertEquals(JsonPrimitive("high"), dispatch.extra["quality"])
        assertTrue(MediaImageNote.QUALITY_DOWNGRADED in dispatch.notes)
    }

    @Test
    fun `complexity is dropped for models that do not document it`() {
        val options = MediaImageOptions(complexity = MediaImageComplexity.LOW)

        val twoSix = options.dispatchFor("gpt-image-2.6-sunburst", MediaCreationParams())
        assertEquals(JsonPrimitive("low"), twoSix.extra["complexity"])

        val twoFive = options.dispatchFor("gpt-image-2.5-sunburst", MediaCreationParams())
        assertNull(twoFive.extra["complexity"])
        assertTrue(MediaImageNote.COMPLEXITY_NOT_SENT in twoFive.notes)
    }

    @Test
    fun `custom sizes follow the documented grid and ratio limits`() {
        val model = "gpt-image-2.6-sunburst"

        assertTrue(MediaImageModels.isUsableSize(model, "1536x1024"))
        assertTrue(MediaImageModels.isUsableSize(model, "2048x1152"))
        assertTrue(MediaImageModels.isUsableSize(model, "3840x2160"))
        // 不是 32 的倍数
        assertFalse(MediaImageModels.isUsableSize(model, "1000x1000"))
        // 太扁
        assertFalse(MediaImageModels.isUsableSize(model, "2048x512"))
        // 太大
        assertFalse(MediaImageModels.isUsableSize(model, "4096x2160"))
        assertFalse(MediaImageModels.isUsableSize(model, "4096x4096"))
    }

    @Test
    fun `models without custom sizes keep only the standard presets`() {
        val options = MediaImageOptions()
        val custom = options.dispatchFor("gpt-image-1.5", MediaCreationParams(resolution = "2048x1152"))

        assertNull(custom.resolution)
        assertTrue(MediaImageNote.SIZE_NOT_SENT in custom.notes)

        val standard = options.dispatchFor("gpt-image-1.5", MediaCreationParams(resolution = "1024x1536"))
        assertEquals("1024x1536", standard.resolution)
        assertTrue(standard.notes.isEmpty())
    }

    @Test
    fun `square requests are flagged because the API decides the real pixels`() {
        val dispatch = MediaImageOptions().dispatchFor("gpt-image-2.6-sunburst", MediaCreationParams(resolution = "1024x1024"))

        assertEquals("1024x1024", dispatch.resolution)
        assertTrue(MediaImageNote.ACTUAL_SIZE_MAY_DIFFER in dispatch.notes)
    }

    @Test
    fun `aspect ratio becomes a pixel size when the API needs one`() {
        val dispatch = MediaImageOptions().dispatchFor("gpt-image-2.6-flare", MediaCreationParams(aspectRatio = "16:9"))

        assertEquals("1536x864", dispatch.resolution)
        assertTrue(MediaImageNote.SIZE_FROM_ASPECT_RATIO in dispatch.notes)

        val square = MediaImageOptions().dispatchFor("gpt-image-2.6-flare", MediaCreationParams(aspectRatio = "1:1"))
        assertEquals("1024x1024", square.resolution)
    }

    @Test
    fun `request carries image options while public fields still win`() {
        val params = MediaCreationParams(
            count = 2,
            resolution = "1024x1536",
            image = MediaImageOptions(format = MediaImageFormat.WEBP, quality = MediaImageQuality.HIGH),
        )
        val request = buildMediaGenerationRequest(
            prompt = "海报",
            params = params,
            assets = emptyList(),
            inputUrls = emptyList(),
            capabilities = openAIImage,
            imageModelId = "gpt-image-2.6-sunburst",
        )

        assertEquals("1024x1536", request.resolution)
        assertEquals(2, request.count)
        assertEquals(JsonPrimitive("webp"), request.extraParameters["output_format"])
        assertEquals(JsonPrimitive("high"), request.extraParameters["quality"])
        assertEquals(JsonPrimitive(100), request.extraParameters["output_compression"])
    }

    @Test
    fun `request without an image model carries no image extras`() {
        val request = buildMediaGenerationRequest(
            prompt = "海报",
            params = MediaCreationParams(image = MediaImageOptions(format = MediaImageFormat.JPEG)),
            assets = emptyList(),
            inputUrls = emptyList(),
            capabilities = openAIImage,
        )

        assertTrue(request.extraParameters.isEmpty())
    }

    @Test
    fun `image params survive serialization and unsupported filtering`() {
        val params = MediaCreationParams(
            count = 1,
            resolution = "1536x1024",
            image = MediaImageOptions(
                format = MediaImageFormat.JPEG,
                quality = MediaImageQuality.HIGH,
                complexity = MediaImageComplexity.MEDIUM,
            ),
            exportMode = MediaImageExportMode.REENCODE,
            exportWidth = 2000,
            exportHeight = 1200,
            exportFit = MediaImageExportFit.COVER,
        )
        val json = Json.encodeToString(MediaCreationParams.serializer(), params)
        assertEquals(params, Json.decodeFromString(MediaCreationParams.serializer(), json))

        // 公共字段被能力过滤，图像选项与导出设置原样保留
        assertEquals(params, params.supportedBy(openAIImage))
    }

    @Test
    fun `export target needs both sides`() {
        assertFalse(MediaCreationParams().hasExportTarget)
        assertFalse(MediaCreationParams(exportWidth = 1024).hasExportTarget)
        assertFalse(MediaCreationParams(exportWidth = 0, exportHeight = 0).hasExportTarget)
        assertTrue(MediaCreationParams(exportWidth = 1024, exportHeight = 768).hasExportTarget)
    }

    @Test
    fun `default quality is high only when nothing was chosen`() {
        assertEquals(MediaImageQuality.HIGH, MediaImageOptions().effectiveQualityOrHigh())
        assertEquals(MediaImageQuality.AUTO, MediaImageOptions(quality = MediaImageQuality.AUTO).effectiveQualityOrHigh())
        assertEquals(MediaImageQuality.LOW, MediaImageOptions(quality = MediaImageQuality.LOW).effectiveQualityOrHigh())
    }

    @Test
    fun `quality keys round trip for persisted values`() {
        MediaImageQuality.entries.forEach {
            assertEquals(it, MediaImageQuality.fromKey(it.key))
        }
        MediaImageFormat.entries.forEach {
            assertEquals(it, MediaImageFormat.fromKey(it.key))
        }
        assertNull(MediaImageQuality.fromKey("standard"))
        assertEquals(MediaImageFormat.JPEG, MediaImageFormat.fromMimeType("image/jpeg"))
    }
}

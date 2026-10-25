#!/usr/bin/env python3
"""rk-exec-server.read_tail 回归测试（review P2）。

历史问题：truncated 只在文件 > MAX_TAIL 时为真，按行丢掉头部日志仍报 false，
客户端因此认为拿到了完整输出。用例覆盖行裁剪 / 字节裁剪 / 少行 / 空文件 /
单行超 MAX_TAIL / UTF-8 边界。

运行：python3 fork/test_rk_exec_server.py
"""
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("rk_exec_server", os.path.join(HERE, "rk-exec-server.py"))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)  # __main__ 保护下只定义函数，不启动服务

MAX_TAIL = server.MAX_TAIL


class ReadTailTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "job.log")

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, data) -> int:
        raw = data.encode() if isinstance(data, str) else data
        with open(self.path, "wb") as fh:
            fh.write(raw)
        return len(raw)

    def test_line_cut_sets_truncated(self):
        """500 行请求 400 行：头部 100 行被丢，必须报 truncated。"""
        self.write("".join("line%d\n" % i for i in range(1, 501)))
        tail, truncated = server.read_tail(self.path, 400)
        self.assertTrue(truncated, "按行裁剪必须置 truncated=True")
        self.assertEqual(400, len(tail.splitlines()))
        self.assertTrue(tail.startswith("line101"), tail[:20])
        self.assertTrue(tail.endswith("line500"), tail[-20:])

    def test_byte_cut_sets_truncated(self):
        """单行都很短但总量超 MAX_TAIL：按字节裁剪，同时必报 truncated。"""
        size = self.write("".join("%06d-abcdefghij\n" % i for i in range(20000)))
        self.assertGreater(size, MAX_TAIL)
        tail, truncated = server.read_tail(self.path, 400)
        self.assertTrue(truncated)
        self.assertEqual(400, len(tail.splitlines()))
        self.assertTrue(tail.endswith("019999-abcdefghij"))

    def test_single_huge_line_is_kept_and_flagged(self):
        """一行就超 MAX_TAIL：不能返回空串，只保留尾部字节并报截断。"""
        size = self.write(b"x" * (MAX_TAIL * 2))
        tail, truncated = server.read_tail(self.path, 400)
        self.assertTrue(truncated)
        self.assertGreater(len(tail), 0)
        self.assertLessEqual(len(tail), size)

    def test_empty_file_is_not_truncated(self):
        self.write("")
        self.assertEqual(("", False), server.read_tail(self.path, 400))

    def test_missing_log_is_not_truncated(self):
        self.assertEqual(("", False), server.read_tail(self.path, 400))

    def test_fewer_lines_than_requested(self):
        """少行不能误报截断（旧实现也会错报的唯一场景之外的反例）。"""
        self.write("only two\nlines\n")
        tail, truncated = server.read_tail(self.path, 400)
        self.assertFalse(truncated)
        self.assertEqual("only two\nlines", tail)

    def test_exact_line_count_is_not_truncated(self):
        self.write("".join("l%d\n" % i for i in range(400)))
        tail, truncated = server.read_tail(self.path, 400)
        self.assertFalse(truncated)
        self.assertEqual(400, len(tail.splitlines()))

    def test_blank_lines_are_not_truncation(self):
        """纯空行文件：只保留原样空行，不算截断。"""
        self.write("\n" * 10)
        tail, truncated = server.read_tail(self.path, 400)
        self.assertFalse(truncated)
        self.assertEqual("", tail.strip())

    def test_zero_or_negative_lines_keeps_last_line(self):
        """行数下界为 1：lines=0 时 [-0:] 会返回整份日志，必须挡住。"""
        self.write("a\nb\nc\n")
        self.assertEqual("c", server.read_tail(self.path, 0)[0])
        self.assertEqual("c", server.read_tail(self.path, -5)[0])

    def test_utf8_boundary_keeps_first_char_intact(self):
        """字节切点落在 3 字节汉字的中间时，tail 不能以 U+FFFD 开头。"""
        # 前缀 (MAX_TAIL-1) 字节 + 汉字：切点 = 6N-1，当 N=1 落在"文"的续字节上
        self.write("a" * (MAX_TAIL - 1) + "中文" * 100)
        tail, truncated = server.read_tail(self.path, 5)
        self.assertTrue(truncated)
        self.assertFalse(tail.startswith("\ufffd"), repr(tail[:4]))
        self.assertNotIn("\ufffd", tail)
        self.assertTrue(tail.endswith("中文"))
        self.assertGreater(len(tail), 0)

    def test_short_utf8_log_is_not_truncated(self):
        self.write("构建完成\n退出码 0\n")
        tail, truncated = server.read_tail(self.path, 400)
        self.assertFalse(truncated)
        self.assertEqual("构建完成\n退出码 0", tail)


if __name__ == "__main__":
    unittest.main(verbosity=2)

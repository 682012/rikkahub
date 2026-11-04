import importlib.util,json,pathlib,tempfile,unittest
spec=importlib.util.spec_from_file_location('firebase_preflight',pathlib.Path(__file__).with_name('verify-firebase-config.py'))
f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)

class FirebasePreflightTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)
  (self.root/'app/src/debug').mkdir(parents=True)
  (self.root/'app/build.gradle.kts').write_text('applicationId = "sample.fork"\ndebug { applicationIdSuffix = ".debug" }')
  self.write('app/google-services.json','sample.fork');self.write('app/src/debug/google-services.json','sample.fork.debug')
 def tearDown(self):self.tmp.cleanup()
 def write(self,path,package):
  x={'project_info':{'project_id':'test-only','project_number':'123'},'client':[{'client_info':{'mobilesdk_app_id':'test-fixture','android_client_info':{'package_name':package}},'api_key':[{'current_key':'fixture-not-a-key'}]}]}
  (self.root/path).write_text(json.dumps(x))
 def test_both_configs(self):
  app,suffix,_=f.read_gradle_identity(self.root);self.assertEqual((app,suffix),('sample.fork','.debug'))
  self.assertEqual(f.verify_one(self.root,'debug',app+suffix)['package'],'sample.fork.debug')
 def test_missing_debug(self):
  (self.root/'app/src/debug/google-services.json').unlink()
  with self.assertRaises(f.Failure) as e:f.verify_one(self.root,'debug','sample.fork.debug')
  self.assertEqual(e.exception.code,f.EXIT_MISSING)
 def test_wrong_package(self):
  self.write('app/src/debug/google-services.json','sample.fork')
  with self.assertRaises(f.Failure) as e:f.verify_one(self.root,'debug','sample.fork.debug')
  self.assertEqual(e.exception.code,f.EXIT_PACKAGE)
 def test_malformed_json(self):
  (self.root/'app/src/debug/google-services.json').write_text('{bad')
  with self.assertRaises(f.Failure) as e:f.verify_one(self.root,'debug','sample.fork.debug')
  self.assertEqual(e.exception.code,f.EXIT_STRUCTURE)
 def test_missing_client(self):
  (self.root/'app/src/debug/google-services.json').write_text('{"project_info":{"project_id":"test","project_number":"1"},"client":[]}')
  with self.assertRaises(f.Failure):f.verify_one(self.root,'debug','sample.fork.debug')
 def test_missing_mobile_app_id(self):
  p=self.root/'app/src/debug/google-services.json';x=json.loads(p.read_text());del x['client'][0]['client_info']['mobilesdk_app_id'];p.write_text(json.dumps(x))
  with self.assertRaises(f.Failure):f.verify_one(self.root,'debug','sample.fork.debug')
if __name__=='__main__':unittest.main()

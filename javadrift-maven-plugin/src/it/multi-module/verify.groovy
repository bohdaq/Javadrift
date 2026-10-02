def log = new File(basedir, 'build.log').text
assert log.contains('0 findings.')
assert new File(basedir, 'api/target/classes/fixture/Order.class').exists()
assert new File(basedir, 'impl/target/classes/fixture/Client.class').exists()
return true

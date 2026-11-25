'use strict';
const path = require('node:path');
async function main() {
  const lib = process.argv[2], project = 'warehouseqa-8c262';
  const {getProjectDefaultAccount} = require(path.join(lib, 'auth.js'));
  const {requireAuth} = require(path.join(lib, 'requireAuth.js'));
  const {Client} = require(path.join(lib, 'apiv2.js'));
  await requireAuth({project, ...getProjectDefaultAccount(process.cwd())});
  const usage = new Client({urlPrefix:'https://serviceusage.googleapis.com', apiVersion:'v1', auth:true});
  const iam = new Client({urlPrefix:'https://iam.googleapis.com', apiVersion:'v1', auth:true});
  const report = {project};
  try {report.fcm = (await usage.get(`/projects/${project}/services/fcm.googleapis.com`)).body.state;}
  catch (error) {report.fcm = `HTTP ${error.status || error.original?.status || 'unavailable'}`;}
  try {report.senderExists = !!(await iam.get(`/projects/${project}/serviceAccounts/invenai-fcm-sender@${project}.iam.gserviceaccount.com`)).body.email;}
  catch (error) {report.senderExists = false;report.senderLookup = error.status || error.original?.status || 'unavailable';}
  console.log(JSON.stringify(report));
}
main().catch(error => {console.error('Push environment inspection failed: '+error.message.split('\n')[0]);process.exitCode=1;});

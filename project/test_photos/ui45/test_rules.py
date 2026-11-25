"""Loopback-only Firestore rules checks and Android emulator fixture setup."""
import copy
import hashlib
import json
from pathlib import Path
import urllib.request
import urllib.error

folder=Path(__file__).resolve().parent;project=folder.parents[1]
PROJECT='warehouseqa-8c262'
FIRESTORE='http://127.0.0.1:18861/v1/'
AUTH='http://127.0.0.1:18862/identitytoolkit.googleapis.com/v1/'
BASE=f'projects/{PROJECT}/databases/(default)/documents'
report=[]

def request(base,path,data=None,token=None,method=None):
    assert base in (FIRESTORE,AUTH)
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(base+path,data=json.dumps(data).encode() if data is not None else None,
                               headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as response:return response.status,json.load(response)
    except urllib.error.HTTPError as error:return error.code,json.loads(error.read())

def encode(value):
    if type(value) is bool:return {'booleanValue':value}
    if type(value) is int:return {'integerValue':str(value)}
    if type(value) is float:return {'doubleValue':value}
    if value is None:return {'nullValue':None}
    if isinstance(value,list):return {'arrayValue':{'values':[encode(v) for v in value]}}
    if isinstance(value,dict):return {'mapValue':{'fields':{k:encode(v) for k,v in value.items()}}}
    return {'stringValue':str(value)}

def account(email):
    body=dict(email=email,password='Ui45LocalOnly!2026',returnSecureToken=True)
    code,result=request(AUTH,'accounts:signUp?key=local-test',body)
    if code!=200:code,result=request(AUTH,'accounts:signInWithPassword?key=local-test',body)
    assert code==200,(code,result)
    return result['localId'],result['idToken']

def seed(path,data):
    code,result=request(FIRESTORE,BASE+'/'+path,{'fields':{k:encode(v) for k,v in data.items()}},'owner','PATCH')
    assert code==200,(code,result)

def create(name,data,token,timestamps=True):
    write={'update':{'name':BASE+'/auditAlerts/'+name,'fields':{k:encode(v) for k,v in data.items()}},
           'currentDocument':{'exists':False}}
    if timestamps:write['updateTransforms']=[{'fieldPath':key,'setToServerValue':'REQUEST_TIME'} for key in ('createdAt','updatedAt')]
    return request(FIRESTORE,BASE+':commit',{'writes':[write]},token)

def change(name,changes,token):
    write={'update':{'name':BASE+'/auditAlerts/'+name,'fields':{k:encode(v) for k,v in changes.items()}},
           'updateMask':{'fieldPaths':list(changes)},
           'updateTransforms':[{'fieldPath':'updatedAt','setToServerValue':'REQUEST_TIME'}]}
    return request(FIRESTORE,BASE+':commit',{'writes':[write]},token)

def check(name,response,allowed):
    code,result=response
    assert (200<=code<300)==allowed,(name,code,result)
    report.append(dict(case=name,status=code,expected='allow' if allowed else 'deny'))

qa,qa_token=account('rules-qa-ui45@invenai.test')
loader,loader_token=account('rules-loader-ui45@invenai.test')
other,other_token=account('rules-other-ui45@invenai.test')
company='UI45-RULES'
for uid,role,tenant in [(qa,'QA',company),(loader,'LOADER',company),(other,'QA','UI45-OTHER')]:
    seed('warehouseMemberships/'+uid,dict(companyCode=tenant,role=role))
stock=dict(companyCode=company,itemCode='ONE',itemName='One',warehouse='Rule Warehouse',location='A01-1F',
           quantity=1,status='NORMAL',isTestData=True)
seed('inventory/UI45-RULES_ONE',stock)
data=dict(companyCode=company,qaUid=qa,jobId='a'*32,inventoryId='UI45-RULES_ONE',warehouse='Rule Warehouse',
          location='A01-1F',expectedItemCode='ONE',observedItemCode='TWO',expectedItemName='One',observedItemName='Two',
          targetLocation='A02-1F',isTest=True,status='OPEN',responderUid='',note='')
def key(name):return 'AI_'+hashlib.sha256(name.encode()).hexdigest()
primary=key('primary')
check('QA creates owned finding',create(primary,data,qa_token),True)
for name,token in [('unauthenticated',None),('loader creates',loader_token),('other company creates',other_token)]:
    check(name,create(key(name),data,token),False)
for name,changes in [
    ('spoof creator',dict(qaUid=loader)),('spoof company',dict(companyCode='UI45-OTHER')),
    ('wrong stock SKU',dict(expectedItemCode='FAKE')),('wrong stock location',dict(location='Z99-9F')),
    ('wrong warehouse',dict(warehouse='Other Warehouse')),('test scope spoof',dict(isTest=False)),
    ('unknown field',dict(admin=True)),('oversized note',dict(note='x'*501)),
    ('unbounded job',dict(jobId='bad')),('same product',dict(observedItemCode='ONE')),
    ('path traversal',dict(inventoryId='bad/path')),('invalid status',dict(status='DONE'))]:
    candidate={**data,**changes}
    check(name,create(key(name),candidate,qa_token),False)
missing=copy.deepcopy(data);missing.pop('inventoryId')
check('required field missing',create(key('missing'),missing,qa_token),False)
check('missing server timestamps',create(key('timestamp'),data,qa_token,False),False)
check('loader reads same company',request(FIRESTORE,BASE+'/auditAlerts/'+primary,token=loader_token),True)
check('other company cannot read',request(FIRESTORE,BASE+'/auditAlerts/'+primary,token=other_token),False)
query={'structuredQuery':{'from':[{'collectionId':'auditAlerts'}],
    'where':{'fieldFilter':{'field':{'fieldPath':'companyCode'},'op':'EQUAL','value':{'stringValue':company}}}}}
check('actual company query',request(FIRESTORE,BASE+':runQuery',query,loader_token),True)
check('unscoped list denied',request(FIRESTORE,BASE+':runQuery',{'structuredQuery':{'from':[{'collectionId':'auditAlerts'}]}},loader_token),False)
check('loader cannot resolve',change(primary,dict(status='RESOLVED'),loader_token),False)
check('immutable creator',change(primary,dict(qaUid=loader),qa_token),False)
check('immutable scope',change(primary,dict(isTest=False),qa_token),False)
check('loader requests review',change(primary,dict(status='REVIEW_REQUESTED',responderUid=loader,note='Corrected'),loader_token),True)
check('loader cannot reset',change(primary,dict(status='OPEN',responderUid=''),loader_token),False)
check('QA requests correction again',change(primary,dict(status='OPEN',responderUid='',note='Recheck'),qa_token),True)
check('loader responds again',change(primary,dict(status='REVIEW_REQUESTED',responderUid=loader,note='Done'),loader_token),True)
check('QA resolves',change(primary,dict(status='RESOLVED'),qa_token),True)
check('resolved cannot be reopened',change(primary,dict(status='OPEN',responderUid=''),qa_token),False)
code,after=request(FIRESTORE,BASE+'/inventory/UI45-RULES_ONE',token=qa_token)
assert code==200 and after['fields']['quantity']==encode(1) and after['fields']['status']==encode('NORMAL')

# Seed Android SDK tests in a separate company. These writes remain loopback-only.
for email,role in [('qa-ui45@invenai.test','QA'),('loader-ui45@invenai.test','LOADER')]:
    uid,_=account(email);seed('warehouseMemberships/'+uid,dict(companyCode='ABC-002',role=role))
inventory=json.loads((project/'test_photos/paired44/inventory_readback.json').read_text(encoding='utf-8'))
for doc_id,fields in inventory:seed('inventory/'+doc_id,fields)
summary=dict(emulator_only=True,project=PROJECT,firestore_host='127.0.0.1:18861',cases=len(report),
    passed=True,stock_quantity_and_status_unchanged=True,android_inventory_seeded=len(inventory),checks=report)
(folder/'rules-verification.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in summary.items() if k!='checks'}))

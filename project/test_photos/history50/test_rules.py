"""Atomic audit history and tenant isolation checks, loopback-only."""
from pathlib import Path
import hashlib,json,uuid
helpers=Path(__file__).resolve().parent.parent/'approval49'/'test_rules.py'
exec(compile(helpers.read_text().split('suffix=uuid.uuid4()')[0],str(helpers),'exec'))

company='HISTORY50'
qa=account('qa-history50');loader=account('loader-history50');other=account('other-history50')
for actor,role,tenant in [(qa,'QA',company),(loader,'LOADER',company),(other,'QA','OTHER50')]:
    seed('warehouseMemberships/'+actor[0],dict(companyCode=tenant,role=role))
stock=dict(companyCode=company,itemCode='EXPECTED',itemName='Expected',quantity=12,location='A01-1F',
           warehouse='H50 warehouse',status='NORMAL',pendingQuantity=0,pendingLocation='',isTestData=True)
seed('inventory/HISTORY50_EXPECTED',stock)
seed('inventory/HISTORY50_LEGACY',{**stock,'itemCode':'LEGACY','itemName':'Legacy','location':'B01-1F'})
alert_id='AI_'+hashlib.sha256(uuid.uuid4().bytes).hexdigest()
alert=dict(companyCode=company,qaUid=qa[0],jobId=uuid.uuid4().hex,inventoryId='HISTORY50_EXPECTED',
    warehouse=stock['warehouse'],location=stock['location'],expectedItemCode='EXPECTED',expectedItemName='Expected',
    observedItemCode='OBSERVED',observedItemName='Observed',targetLocation='A02-1F',isTest=True,
    status='OPEN',responderUid='',note='')
def history(event_id,kind='AUDIT_DETECTION',before='',after='OPEN',actor=qa,note='',changes=None):
    data=dict(alertId=alert_id,inventoryId=alert['inventoryId'],actorUid=actor[0],type=kind,
      beforeStatus=before,afterStatus=after,expectedItemCode='EXPECTED',expectedItemName='Expected',
      observedItemCode='OBSERVED',observedItemName='Observed',warehouse=stock['warehouse'],
      fromLocation='A01-1F',toLocation='A02-1F',isTest=True,note=note)
    return write(f'auditHistory/{company}/events/{event_id}',{**data,**(changes or {})},('occurredAt',))
event=uuid.uuid4().hex[:20]
create=write('auditAlerts/'+alert_id,{**alert,'lastHistoryEventId':event},('createdAt','updatedAt'))
check('linked finding without event rejected',commit([create],qa[1]),False)
check('event without finding rejected',commit([history(event)],qa[1]),False)
check('atomic finding and event',commit([create,history(event)],qa[1]),True)
check('company history list allowed',call(FS,BASE+f'/auditHistory/{company}/events',token=loader[1]),True)
check('cross-company history list blocked',call(FS,BASE+f'/auditHistory/{company}/events',token=other[1]),False)
check('anonymous history blocked',call(FS,BASE+f'/auditHistory/{company}/events'),False)
check('event immutable',commit([write(f'auditHistory/{company}/events/{event}',dict(note='tamper'),(),True)],qa[1]),False)
check('event delete blocked',call(FS,BASE+f'/auditHistory/{company}/events/{event}',token=qa[1],method='DELETE'),False)

def transition(kind,before,after,actor,note='',history_changes=None):
    event=uuid.uuid4().hex[:20]
    responder=loader[0] if after in ('REVIEW_REQUESTED','RESOLVED') else ''
    change=write('auditAlerts/'+alert_id,dict(status=after,note=note,responderUid=responder,lastHistoryEventId=event),('updatedAt',),True)
    return [change,history(event,kind,before,after,actor,note,history_changes)]
for name,change in [('actor spoof',dict(actorUid=qa[0])),('wrong source',dict(fromLocation='OTHER')),
                     ('wrong destination',dict(toLocation='OTHER')),('wrong SKU',dict(observedItemCode='FAKE')),
                     ('wrong warehouse',dict(warehouse='OTHER')),('wrong event type',dict(type='AUDIT_APPROVAL')),
                     ('wrong prior status',dict(beforeStatus='RESOLVED')),('wrong note',dict(note='not the report')),
                     ('test flag spoof',dict(isTest=False)),('unknown quantity field',dict(quantity=12))]:
    check(name,commit(transition('AUDIT_RELOCATION','OPEN','REVIEW_REQUESTED',loader,'moved',change),loader[1]),False)
check('QA cannot impersonate loader relocation',commit(transition('AUDIT_RELOCATION','OPEN','REVIEW_REQUESTED',qa),qa[1]),False)
check('cross-company transition blocked',commit(transition('AUDIT_RELOCATION','OPEN','REVIEW_REQUESTED',other),other[1]),False)
check('atomic relocation report',commit(transition('AUDIT_RELOCATION','OPEN','REVIEW_REQUESTED',loader,'moved'),loader[1]),True)
check('loader cannot approve own report',commit(transition('AUDIT_APPROVAL','REVIEW_REQUESTED','RESOLVED',loader),loader[1]),False)
check('atomic QA rework request',commit(transition('AUDIT_REJECTION','REVIEW_REQUESTED','OPEN',qa,'move again'),qa[1]),True)
check('second relocation report',commit(transition('AUDIT_RELOCATION','OPEN','REVIEW_REQUESTED',loader,'moved again'),loader[1]),True)
check('atomic QA approval',commit(transition('AUDIT_APPROVAL','REVIEW_REQUESTED','RESOLVED',qa,'verified'),qa[1]),True)
duplicate=uuid.uuid4().hex[:20]
check('late duplicate history without transition blocked',commit([history(duplicate,'AUDIT_APPROVAL','REVIEW_REQUESTED','RESOLVED',qa,'verified')],qa[1]),False)
for name,to,qty,allowed in [('compatibility with old stock-only report','C01-1F',12,True),
                            ('reported relocation location and quantity','C02-1F',9,True),
                            ('invented relocation destination','C99-9F',9,False)]:
    sku='COMPAT'+uuid.uuid4().hex[:8]
    item_id=company+'_'+sku
    seed('inventory/'+item_id,{**stock,'itemCode':sku,'itemName':'Compatibility','location':'C01-1F','status':'ANOMALY'})
    event_id=uuid.uuid4().hex[:20]
    movement=write('inventory/'+item_id,dict(status='PENDING_QA',pendingLocation='C02-1F',pendingQuantity=9,
        userId=loader[0],lastEventId=event_id),('updatedAt',),True)
    log=write(f'stockHistory/{company}/events/{event_id}',dict(inventoryId=item_id,actorUid=loader[0],type='REVIEW_REQUEST',
        itemCode=sku,itemName='Compatibility',warehouse=stock['warehouse'],fromLocation='C01-1F',toLocation=to,
        beforeQuantity=12,afterQuantity=qty),('occurredAt',))
    check(name,commit([movement,log],loader[1]),allowed)
code,after=call(FS,BASE+'/inventory/HISTORY50_EXPECTED',token=qa[1])
assert code==200 and after['fields']=={k:enc(v) for k,v in stock.items()}
code,events=call(FS,BASE+f'/auditHistory/{company}:runQuery',{'structuredQuery':{
    'from':[{'collectionId':'events'}],'where':{'fieldFilter':{'field':{'fieldPath':'alertId'},'op':'EQUAL','value':enc(alert_id)}}}},qa[1])
assert code==200
rows=[d['document'] for d in events if 'document' in d]
assert len(rows)==5,len(rows)
summary=dict(emulatorOnly=True,passed=True,cases=len(checks),auditStockUnchanged=True,fullReworkCycleEvents=len(rows),checks=checks)
Path(__file__).with_name('rules-verification.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k!='checks'}))

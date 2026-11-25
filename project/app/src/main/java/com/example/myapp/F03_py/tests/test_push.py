"""FCM transport is a recording fake. No production device, credential or stock writes."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
import f03_api as api
import f06_push as push
from f03_auth import AuditUser, require_user


class RecordingSender:
    ready = True

    def __init__(self):
        self.sent = []
        self.fail = set()
        self.invalid = set()

    def send(self, token, data):
        if token in self.invalid:
            raise push.InvalidDeviceToken()
        if token in self.fail:
            raise RuntimeError('Simulated offline transport')
        self.sent.append((token, data))


class PushApi(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = push.PushStore(Path(self.temp.name)/'push.sqlite3')
        self.sender = RecordingSender()
        api.app.state.f06_store = self.store
        api.app.state.f06_sender = self.sender
        self.addCleanup(lambda: delattr(api.app.state, 'f06_store'))
        self.addCleanup(lambda: delattr(api.app.state, 'f06_sender'))
        self.user = AuditUser('qa', 'ABC-002', 'local-test')
        api.app.dependency_overrides[require_user] = lambda: self.user
        self.addCleanup(api.app.dependency_overrides.clear)
        self.roles = {'qa': ('ABC-002', 'QA'), 'loader': ('ABC-002', 'LOADER'),
                      'other': ('OTHER', 'LOADER')}
        self.alert = dict(companyCode='ABC-002', status='OPEN', updatedAt='2026-11-25T04:00:00Z')
        self.lookup = patch.object(push, 'fields_for', self.read)
        self.lookup.start();self.addCleanup(self.lookup.stop)
        self.client = TestClient(api.app)
        self.id = 'AI_'+'a'*64
        self.token = 'local-device-token-'+'a'*32

    def read(self, user, collection, document):
        if collection == 'warehouseMemberships':
            company, role = self.roles.get(document, ('UNKNOWN', 'NONE'))
            return dict(companyCode=company, role=role)
        return self.alert

    def register(self, uid='loader', device='b'*32, token=None):
        company = self.roles[uid][0]
        self.user = AuditUser(uid, company, 'local-test')
        return self.client.post('/f06/push/devices', json=dict(device_id=device, token=token or self.token))

    def send(self, **params):
        self.user = AuditUser('qa', 'ABC-002', 'local-test')
        return self.client.post('/f06/push/alerts/'+self.id, params=params)

    def test_registration_is_loader_only_and_client_cannot_spoof_a_company_or_user(self):
        self.assertEqual(self.register('qa').status_code, 403)
        self.assertEqual(self.register().status_code, 200)
        bad=self.client.post('/f06/push/devices', json=dict(device_id='c'*32, token=self.token, companyCode='OTHER'))
        self.assertEqual(bad.status_code, 422)
        self.assertEqual(self.store.recipients('ABC-002'), [('loader',self.token)])

    def test_only_same_company_devices_receive_opaque_identifiers(self):
        self.register()
        self.register('other', 'c'*32, 'other-device-token-'+'b'*32)
        response=self.send()
        self.assertEqual(response.status_code,200,response.text)
        self.assertEqual(len(self.sender.sent),1)
        token,data=self.sender.sent[0]
        self.assertEqual(token,self.token)
        self.assertEqual(data['recipient_uid'],'loader')
        self.assertEqual(set(data),{'type','kind','alert_id','recipient_uid','company_code','event_id'})
        self.assertNotIn('location',data)

    def test_sender_rejects_loader_forged_alert_company_and_unknown_kinds(self):
        self.register()
        self.assertEqual(self.client.post('/f06/push/alerts/'+self.id).status_code,403)
        self.alert['companyCode']='OTHER'
        self.assertEqual(self.send().status_code,403)
        self.assertEqual(self.send(kind='notifications').status_code,422)
        self.assertFalse(self.sender.sent)

    def test_repeated_requests_do_not_send_the_same_update_twice(self):
        self.register()
        self.assertEqual(self.send().status_code,200)
        self.assertEqual(self.send().status_code,200)
        self.assertEqual(len(self.sender.sent),1)
        self.alert['updatedAt']='2026-11-25T04:01:00Z'
        self.assertEqual(self.send().status_code,200)
        self.assertEqual(len(self.sender.sent),2)

    def test_closed_and_deleted_findings_are_ignored(self):
        self.register()
        for status in ('RESOLVED','REVIEW_REQUESTED'):
            self.alert['status']=status
            self.assertEqual(self.send().json()['status'],'ignored')
        self.alert=None
        self.assertEqual(self.send().json()['status'],'ignored')
        self.assertFalse(self.sender.sent)

    def test_partial_delivery_retries_only_the_failed_device(self):
        self.register()
        second='second-device-token-'+'c'*32
        self.register('loader','c'*32,second)
        self.sender.fail.add(second)
        self.assertEqual(self.send().status_code,503)
        self.assertEqual(len(self.sender.sent),1)
        self.sender.fail.clear()
        self.assertEqual(self.send().status_code,200)
        self.assertEqual([t for t,_ in self.sender.sent].count(self.token),1)
        self.assertEqual([t for t,_ in self.sender.sent].count(second),1)

    def test_unregistered_tokens_are_removed_without_retrying_valid_devices(self):
        self.register()
        self.sender.invalid.add(self.token)
        self.assertEqual(self.send().status_code,200)
        self.assertEqual(self.store.recipients('ABC-002'),[])

    def test_device_removal_is_scoped_to_the_authenticated_owner(self):
        self.register()
        self.user=AuditUser('qa','ABC-002','local-test')
        body=dict(device_id='b'*32)
        self.assertEqual(self.client.post('/f06/push/devices/remove',json=body).status_code,200)
        self.assertEqual(len(self.store.recipients('ABC-002')),1)
        self.user=AuditUser('loader','ABC-002','local-test')
        self.client.post('/f06/push/devices/remove',json=body)
        self.assertEqual(self.store.recipients('ABC-002'),[])

    def test_unknown_new_owner_cannot_replace_a_device_without_the_same_token(self):
        self.register()
        result=self.register('other','b'*32,'different-device-token-'+'d'*32)
        self.assertEqual(result.status_code,409)
        self.assertEqual(len(self.store.recipients('ABC-002')),1)

    def test_same_installation_can_change_account_without_leaving_an_old_recipient(self):
        self.register()
        self.assertEqual(self.register('other','b'*32).status_code,200)
        self.assertEqual(self.store.recipients('ABC-002'),[])
        self.assertEqual(self.store.recipients('OTHER'),[('other',self.token)])

    def test_sender_not_configured_keeps_alerts_retryable(self):
        self.register()
        self.sender.ready=False
        self.assertEqual(self.send().status_code,503)
        self.assertFalse(self.sender.sent)

    def test_legacy_inventory_anomaly_uses_the_same_secure_push_route(self):
        self.register()
        self.alert['status']='ANOMALY'
        self.assertEqual(self.send(kind='inventory').status_code,200)
        self.assertEqual(self.sender.sent[0][1]['kind'],'inventory')

    def test_original_alert_and_stock_are_not_modified_by_delivery(self):
        self.register()
        original=dict(self.alert)
        self.send()
        self.assertEqual(self.alert,original)

    def test_device_and_token_input_is_bounded_and_authentication_is_required(self):
        self.register()
        for changes in (dict(device_id='../other'), dict(token='x'*4097),dict(token='bad token')):
            body=dict(device_id='b'*32,token=self.token);body.update(changes)
            self.assertEqual(self.client.post('/f06/push/devices',json=body).status_code,422)
        api.app.dependency_overrides.clear()
        self.assertEqual(self.client.post('/f06/push/devices',json=dict(device_id='b'*32,token=self.token)).status_code,401)


class PushStoreTests(unittest.TestCase):
    def test_delivery_lease_prevents_parallel_sends_and_survives_a_server_restart(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'push.sqlite3'
            first=push.PushStore(path);second=push.PushStore(path)
            self.assertTrue(first.claim('event','token-hash'))
            with self.assertRaises(Exception):second.claim('event','token-hash')
            first.finish('event','token-hash',True)
            self.assertFalse(push.PushStore(path).claim('event','token-hash'))

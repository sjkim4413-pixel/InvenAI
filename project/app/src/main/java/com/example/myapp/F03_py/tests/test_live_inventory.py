import copy
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from f03_live_inventory import build_snapshot,warehouse_view,live_catalog,InventoryInputError,InventoryUnavailable
from f03_catalog import match_catalog
from f03_live_ocr import product_evidence,rack_readings,read_location_regions
from f04_live_review import review_live,identify_codes,is_fragment,is_printed_sticker_duplicate


def record(**changes):
    return {'companyCode':'ABC-001','itemCode':'BT-200','itemName':'Bluetooth BT-200','warehouse':'제1물류센터',
            'location':'B구역 R08 (적재랙)-1층 (제1물류센터)','rack':'R08','rackId':'B구역 R08',
            'zone':'B구역','tier':'1층 (제1물류센터)','quantity':50,'status':'NORMAL',**changes}


class LiveInventory(unittest.TestCase):
    def test_printed_sticker_deduplicates_nested_boxes_without_hiding_another_label(self):
        anchor={'id':'whole','bbox':[.10,.20,.53,.36],'confidence':.87,'detection_stable':True}
        nested={'id':'nested','bbox':[.28,.20,.53,.28],'confidence':.64,'detection_stable':True}
        wide={'id':'wide','bbox':[.10,.17,.53,.42],'confidence':.22,'detection_stable':False}
        sticker={'bbox':[.27,.39,.36,.41]}
        other={'bbox':[.32,.277,.41,.294]}
        self.assertTrue(is_printed_sticker_duplicate(nested,anchor,[sticker],sticker))
        self.assertTrue(is_printed_sticker_duplicate(wide,anchor,[sticker],sticker))
        self.assertFalse(is_printed_sticker_duplicate(nested,anchor,[sticker,other],sticker))
        self.assertFalse(is_printed_sticker_duplicate({**wide,'detection_stable':True},anchor,[sticker],sticker))

    def test_printed_beam_sticker_can_link_to_shallow_load_in_test_mode(self):
        raw={'detections':[{'id':'load','bbox':[.486,.454,.586,.510],
                            'confidence':.73,'detection_stable':True}],
             'ocr_tokens':[{'id':'label','text':'QAB03-3F-01','confidence':.91,
                            'bbox':[.500,.530,.567,.548]}]}
        positions=[{'rack':'QAB03','floor':3,'zone':'QAB','side':'01','position_id':'QAB/QAB03/3/01'}]
        self.assertFalse(read_location_regions(raw,positions,[],test_rack_labels=True))
        region,=read_location_regions(raw,positions,[],test_rack_labels=True,printed_stickers=True)
        self.assertEqual(region['detection_id'],'load')
        raw['ocr_tokens'].append({'id':'other','text':'QAB03-3F-02','confidence':.96,
                                  'bbox':[.524,.511,.581,.527]})
        positions.append({**positions[0],'side':'02','position_id':'QAB/QAB03/3/02'})
        self.assertFalse(read_location_regions(raw,positions,[],test_rack_labels=True,printed_stickers=True))

    def test_box_covering_two_stickers_does_not_mask_individual_loads(self):
        raw={'detections':[
                {'id':'wide','bbox':[.1,.2,.9,.5],'confidence':.88,'detection_stable':True},
                {'id':'left','bbox':[.1,.2,.48,.5],'confidence':.45,'detection_stable':True,
                 'scale_predictions':[{'confidence':.45},{'confidence':.39}]},
                {'id':'right','bbox':[.52,.2,.9,.5],'confidence':.72,'detection_stable':True},
            ],'ocr_tokens':[
                {'id':'t1','text':'A01-1F','confidence':.95,'bbox':[.23,.51,.33,.53]},
                {'id':'t2','text':'A02-1F','confidence':.95,'bbox':[.63,.51,.73,.53]},
            ]}
        positions=[{'rack':rack,'floor':1,'zone':None,'side':None,'position_id':rack}
                   for rack in ('A01','A02')]
        test_regions=read_location_regions(raw,positions,[],test_rack_labels=True)
        self.assertEqual({r['detection_id'] for r in test_regions},{'left','right'})
        self.assertEqual([r['detection_id'] for r in read_location_regions(raw,positions,[])],['right'])
        unregistered_label_scene={**raw,'detections':[raw['detections'][0]]}
        self.assertEqual([r['detection_id'] for r in read_location_regions(unregistered_label_scene,positions[1:],[])],['wide'])

    def test_rack_ocr_corrects_i_only_in_numeric_fields(self):
        readings=list(rack_readings('IR04-IF-02'))
        self.assertEqual(len(readings),1)
        parts,normalized=readings[0]
        self.assertEqual((parts['rack'],parts['tier'],parts['side']),('IR04',1,'02'))
        self.assertEqual(normalized,'IR04-1F-02')

    def test_repeated_moderate_detection_is_eligible_only_for_test_rack_label_checks(self):
        snapshot=build_snapshot('ABC-001',[('one',record())])
        positions=warehouse_view(snapshot,'제1물류센터')
        raw={'detections':[{'id':'d1','bbox':[.1,.1,.8,.8],'confidence':.45,'detection_stable':True,
                            'scale_predictions':[{'confidence':.45},{'confidence':.39}]}],
             'ocr_tokens':[{'id':'r','text':'R08-1F','confidence':.90,'bbox':[.42,.81,.50,.83]},
                           {'id':'s','text':'SKU BT-200','confidence':.94,'bbox':[.3,.4,.4,.43]}]}
        live,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertFalse(live['location_verified'])
        test_codes={positions[0]['position_id']:'R08-1F'}
        demo,=review_live(raw,[],snapshot,positions,[],{},test_item_codes=test_codes)['results']
        self.assertTrue(demo['test_label_match'])
        self.assertFalse(demo['product_location_verified'])
        raw['detections'][0]['scale_predictions'][1]['confidence']=.20
        weak,=review_live(raw,[],snapshot,positions,[],{},test_item_codes=test_codes)['results']
        self.assertFalse(weak.get('test_label_match',False))

    def test_nested_detection_is_kept_when_it_has_its_own_rack_label(self):
        whole={'id':'d1','bbox':[.2,.2,.6,.4]}
        part={'id':'d2','bbox':[.22,.2,.58,.31],'confidence':.55,'detection_stable':False}
        self.assertTrue(is_fragment(part,whole,[]))
        self.assertFalse(is_fragment(part,whole,[{'bbox':[.32,.31,.43,.33]}]))

    def test_exact_company_and_all_location_dimensions_are_preserved(self):
        snapshot=build_snapshot('ABC-001',[('one',record())])
        self.assertEqual(snapshot['records'][0]['position_id'],'B/R08/1/-')
        self.assertEqual(snapshot['records'][0]['side'],None)
        self.assertEqual(warehouse_view(snapshot,'제1물류센터')[0]['items'][0]['quantity'],50)
        with self.assertRaises(InventoryInputError):build_snapshot('ABC001',[('one',record())])

    def test_conflicting_missing_and_duplicate_records_do_not_become_expected_stock(self):
        variants=[record(zone='D구역'),record(rack='R09'),record(tier='2층'),
                  record(tier='1층 (제2물류센터)'),record(warehouse='제2물류센터'),record(quantity=True),
                  record(quantity=float('nan')),record(warehouse='',location='B구역 R08-1층',tier='1층')]
        for row in variants:
            result=build_snapshot('ABC-001',[('bad',row)])
            self.assertFalse(result['complete'])
            self.assertEqual(result['records'],[])
        result=build_snapshot('ABC-001',[('one',record()),('duplicate',record())])
        self.assertEqual(result['records'],[])
        self.assertEqual(len(result['issues']),2)

    def test_side_and_zone_do_not_get_collapsed(self):
        left=record(location='B구역 R08-1층-L')
        right=record(location='B구역 R08-1층-R')
        snapshot=build_snapshot('ABC-001',[('L',left),('R',right)])
        self.assertEqual({r['position_id'] for r in snapshot['records']},{'B/R08/1/L','B/R08/1/R'})

    def test_inventory_names_do_not_create_sku_observations(self):
        snapshot=build_snapshot('ABC-001',[('one',record())])
        catalog=live_catalog(snapshot)
        token={'id':'t1','text':'Bluetooth BT-200','confidence':.99,'bbox':[.1,.1,.2,.2]}
        self.assertEqual(match_catalog([token],catalog),[])
        token['text']='SKU BT-200'
        self.assertEqual(match_catalog([token],catalog)[0]['sku'],'BT-200')

    def test_source_changes_invalidate_revision_and_rejected_data_are_included(self):
        first=build_snapshot('ABC-001',[('one',record())])
        second=build_snapshot('ABC-001',[('one',record(quantity=51))])
        self.assertNotEqual(first['revision'],second['revision'])
        third=build_snapshot('ABC-001',[('one',record()),('bad',record(zone='X'))])
        self.assertNotEqual(first['revision'],third['revision'])

    def fixture(self,quantity=50,status='NORMAL'):
        snapshot=build_snapshot('ABC-001',[('one',record(quantity=quantity,status=status))])
        positions=warehouse_view(snapshot,'제1물류센터')
        region={'rack':'POS-test','floor':1,'position_id':'B/R08/1/-','bbox':[0,0,1,1],'confirmation_method':'operator_confirmed'}
        detection={'id':'d1','bbox':[.1,.1,.8,.8],'confidence':.9,'detection_stable':True}
        raw={'detections':[detection],'results':[{'랙번호':'POS-test','단수':1,'recognized_product_ids':[],
               'verification_passed':False,'ocr_max_confidence':0}]}
        code={'id':'code1','format':'QR_CODE','value':json.dumps({'ivenai':1,'kind':'product','companyCode':'ABC-001','itemCode':'BT-200'}),
              'bbox':[.2,.2,.3,.3],'checksum_valid':None}
        return raw,code,snapshot,positions,[region]

    def test_decoded_registered_code_is_independent_of_expected_sku(self):
        raw,code,snapshot,positions,regions=self.fixture()
        result=review_live(raw,[code],snapshot,positions,regions,{})
        row=result['results'][0]
        self.assertTrue(row['product_location_verified'])
        self.assertEqual(row['expected_quantity'],50)
        self.assertIsNone(row['observed_quantity'])
        self.assertFalse(row['verification_passed'])
        positions[0]['items'][0]['sku']='OTHER'
        changed=review_live(raw,[code],snapshot,positions,regions,{})['results'][0]
        self.assertEqual(changed['ai_product_ids'],['BT-200'])
        self.assertFalse(changed['product_location_verified'])

    def test_missing_location_clipped_code_and_ambiguous_objects_never_verify(self):
        raw,code,snapshot,positions,regions=self.fixture()
        self.assertFalse(review_live(raw,[code],snapshot,positions,[],{})['results'][0]['location_verified'])
        code['bbox']=[.7,.7,.95,.95]
        self.assertFalse(review_live(raw,[code],snapshot,positions,regions,{})['results'][0]['product_location_verified'])
        code['bbox']=[.2,.2,.3,.3];raw['detections'].append(copy.deepcopy(raw['detections'][0]))
        self.assertFalse(review_live(raw,[code],snapshot,positions,regions,{})['results'][0]['product_location_verified'])

    def test_zero_stock_and_existing_anomaly_are_not_normal(self):
        for qty,status,wanted in [(0,'NORMAL','실제전산_무재고위치에상품탐지'),(50,'ANOMALY','전산이상상태_재확인')]:
            raw,code,snapshot,positions,regions=self.fixture(qty,status)
            row=review_live(raw,[code],snapshot,positions,regions,{})['results'][0]
            self.assertFalse(row['product_location_verified'])
            self.assertEqual(row['review_status'],wanted)

    def test_wrong_company_qr_and_unregistered_codes_are_not_skus(self):
        raw,code,snapshot,positions,regions=self.fixture()
        data=json.loads(code['value']);data['companyCode']='OTHER';code['value']=json.dumps(data)
        identified,unknown=identify_codes([code],snapshot,{})
        self.assertEqual(identified,[]);self.assertEqual(len(unknown),1)

    def test_ocr_review_holds_weak_labels_and_reports_explicit_unknown_code(self):
        raw,_,snapshot,positions,_=self.fixture()
        raw['ocr_tokens']=[
            {'id':'t1','text':'SKU: UNKNOWN-123','confidence':.98,'bbox':[.2,.2,.3,.3]},
            {'id':'t2','text':'R08-1F','confidence':.95,'bbox':[.4,.81,.53,.83]},
        ]
        row,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertEqual(row['f04_status'],'UNREGISTERED')
        raw['ocr_tokens'][0].update(text='SKU BT-200',confidence=.69)
        row,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertEqual(row['f04_status'],'MANUAL_REVIEW_REQUIRED')
        raw['ocr_tokens'][0]['confidence']=.98
        raw['ocr_tokens'].append({'id':'t3','text':'R08-1F','confidence':.98,'bbox':[.83,.81,.93,.83]})
        row,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertFalse(row['location_verified'])

    def test_split_ocr_preserves_ambiguity_between_distinct_registered_codes(self):
        tokens=[
            {'id':'t1','text':'ITEM CODE: BT','confidence':.98,'bbox':[.2,.3,.32,.33]},
            {'id':'t2','text':'201','confidence':.98,'bbox':[.33,.3,.36,.33]},
        ]
        found=product_evidence(tokens,{'BT-201','BT201','BT-200'})
        self.assertEqual({m['sku'] for m in found},{'BT-201','BT201'})
        tokens[1]['bbox']=[.50,.3,.53,.33]
        self.assertNotIn('BT-201',{m['sku'] for m in product_evidence(tokens,{'BT-201'})})

    def test_pallet_sku_uses_its_own_rack_and_rejects_a_neighboring_load(self):
        raw,_,_,_,_=self.fixture()
        snapshot=build_snapshot('ABC-001',[('here',record()),('other',record(itemCode='BT-201',
            location='B구역 R09-1층',rack='R09',rackId='B구역 R09'))])
        positions=warehouse_view(snapshot,'제1물류센터')
        raw['ocr_tokens']=[
            {'id':'pallet','text':'SKU BT-200','confidence':.97,'bbox':[.22,.81,.39,.84]},
            {'id':'rack','text':'R08-1F','confidence':.98,'bbox':[.49,.81,.60,.84]},
        ]
        row,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertEqual(row['f04_status'],'PRODUCT_LOCATION_MATCH')
        self.assertEqual(row['product_matches'][0]['evidence_area'],'pallet')
        self.assertEqual(row['ocr_token_ids'],['pallet','rack'])
        self.assertFalse(row['quantity_verified'])

        raw['ocr_tokens'][0]['text']='SKU BT-201'
        row,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertEqual(row['f04_status'],'MISPLACED')
        raw['detections'].append({'id':'lower','bbox':[.21,.81,.40,.97],
                                  'confidence':.8,'detection_stable':True})
        rows=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertEqual(rows[0]['f04_status'],'MANUAL_REVIEW_REQUIRED')

    def test_rack_lookalikes_require_a_unique_registered_location(self):
        raw,_,_,_,_=self.fixture()
        here=record(location='B구역 A01-5F',rack='A01',rackId='B구역 A01',tier='5F')
        raw['ocr_tokens']=[
            {'id':'sku','text':'SKU BT-200','confidence':.98,'bbox':[.22,.81,.39,.84]},
            {'id':'rack','text':'AO1-SF','confidence':.92,'bbox':[.49,.81,.60,.84]},
        ]
        snapshot=build_snapshot('ABC-001',[('here',here)])
        positions=warehouse_view(snapshot,'제1물류센터')
        row,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertEqual(row['f04_status'],'PRODUCT_LOCATION_MATCH')
        self.assertEqual(row['location_evidence']['text'],'AO1-SF')
        self.assertEqual(row['location_evidence']['normalized_text'],'A01-5F')

        other={**here,'location':'B구역 AO1-5F','rack':'AO1','rackId':'B구역 AO1'}
        snapshot=build_snapshot('ABC-001',[('here',here),('other',other)])
        positions=warehouse_view(snapshot,'제1물류센터')
        row,=review_live(raw,[],snapshot,positions,[],{})['results']
        self.assertFalse(row['location_verified'])

    def test_sku_lookalikes_need_agreeing_rereads_and_keep_registered_conflicts(self):
        raw,_,_,_,regions=self.fixture()
        registered=record(itemCode='BT-005')
        snapshot=build_snapshot('ABC-001',[('here',registered)])
        positions=warehouse_view(snapshot,'제1물류센터')
        raw['ocr_tokens']=[{'id':'raw','text':'SKU BT-OOS','confidence':.95,'bbox':[.2,.3,.4,.34]}]
        row,=review_live(raw,[],snapshot,positions,regions,{})['results']
        self.assertEqual(row['f04_status'],'MANUAL_REVIEW_REQUIRED')
        self.assertEqual(row['ai_product_ids'],['BT-OOS'])

        raw['ocr_tokens'].append({'id':'retry','text':'SKU BT-005','confidence':.96,'bbox':[.2,.3,.4,.34],
                                  'confirmed_reread':True})
        row,=review_live(raw,[],snapshot,positions,regions,{})['results']
        self.assertEqual(row['f04_status'],'PRODUCT_LOCATION_MATCH')
        self.assertEqual(raw['ocr_tokens'][0]['text'],'SKU BT-OOS')

        other={**registered,'itemCode':'BT-OOS','location':'B구역 R09-1F','rack':'R09','rackId':'B구역 R09'}
        snapshot=build_snapshot('ABC-001',[('here',registered),('other',other)])
        positions=warehouse_view(snapshot,'제1물류센터')
        row,=review_live(raw,[],snapshot,positions,regions,{})['results']
        self.assertEqual(row['f04_status'],'MANUAL_REVIEW_REQUIRED')


if __name__=='__main__':unittest.main()

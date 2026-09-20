import assert from 'node:assert/strict'
import test from 'node:test'
import { appendUsageItems, filterUsageItems } from '../src/features/storeResources/itemSelection.ts'
const brush={id:'brush',name:'牙刷',sku:'BR-01',specification:'独立包装',category:'CONSUMABLE',unit:'支',issueUnit:'盒',issueFactor:12,rowVersion:4}
const cleaner={id:'cleaner',name:'清洁剂',sku:'CL-01',specification:'500毫升/瓶',category:'CLEANING_SUPPLY',unit:'毫升',issueUnit:'瓶',issueFactor:500,rowVersion:2}
test('search supports name, code, specification and category together',()=>{
  assert.deepEqual(filterUsageItems([brush,cleaner],'br-01','ALL'),[brush])
  assert.deepEqual(filterUsageItems([brush,cleaner],'500 瓶','CLEANING_SUPPLY'),[cleaner])
  assert.deepEqual(filterUsageItems([brush,cleaner],'牙刷','LINEN'),[])
})
test('continued selection preserves quantities, deduplicates and uses configured issue units',()=>{
  const existing={itemId:'brush',quantity:'20',unit:'盒',expectedItemVersion:4}
  assert.deepEqual(appendUsageItems([existing],[brush,cleaner,cleaner]),[existing,{itemId:'cleaner',quantity:'',unit:'瓶',expectedItemVersion:2}])
  assert.equal(appendUsageItems([existing],[brush])[0],existing)
})
test('selection has bounded payload size',()=>{
  assert.equal(appendUsageItems([],Array.from({length:101},(_,i)=>({...brush,id:String(i)}))).length,100)
})

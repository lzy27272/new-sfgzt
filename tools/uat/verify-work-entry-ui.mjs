import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.UAT_PLAYWRIGHT_MODULE || 'playwright')
const base = process.env.UAT_WEB_BASE || 'http://127.0.0.1:5189'
assert.equal(new URL(base).hostname, '127.0.0.1', 'component fixtures must run locally')
assert.ok(process.env.UAT_EVIDENCE_ROOT, 'supply an evidence directory outside the repository')
const output = path.resolve(process.env.UAT_EVIDENCE_ROOT)
await mkdir(output, { recursive: true })
const executablePath = ['C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync)
const browser = await chromium.launch({ headless: true, executablePath })
const result = []
try {
  for (const [name, viewport] of [['desktop',{ width:1440,height:1000 }], ['mobile',{ width:390,height:844 }]]) {
    const context = await browser.newContext({ viewport })
    const page = await context.newPage()
    const errors = []
    const submissions = []
    let refreshes = 0
    let records = 0
    page.on('pageerror', error => errors.push(error.message))
    await page.route('**/api/v1/**', async route => {
      const request = route.request()
      const pathname = new URL(request.url()).pathname
      let body = {}
      if (pathname.endsWith('/tasks/targets')) {
        refreshes++
        body = [{ assignment_id:'fo-supervisor',employee_name:'验证前厅员工',position_name:'前厅主管',
          level_code:'M3',org_unit_id:'test-hotel',org_unit_name:'验证门店',hotel_id:'test-hotel',hotel_name:'验证门店' },
        { assignment_id:'housekeeper',employee_name:'验证客房员工',position_name:'客房服务员',
          level_code:'P1',org_unit_id:'test-hotel',org_unit_name:'验证门店',hotel_id:'test-hotel',hotel_name:'验证门店' }]
      } else if (pathname.endsWith('/delegation-candidates')) {
        body = [{ assignment_id:'recipient',employee_name:'值班主管',position_name:'客房主管' }]
      } else if (pathname.endsWith('/work-data/records') && request.method()==='POST') {
        submissions.push(request.postDataJSON())
        body = { id:'record-'+(++records),rowVersion:0,status:'DRAFT' }
      } else if (pathname.includes('/actions/submit')) body = { status:'SUBMITTED',rowVersion:1 }
      else if (pathname.endsWith('/tasks') && request.method()==='POST') body = { id:'task-1',row_version:0 }
      else if (pathname.includes('/tasks/task-1')) body = { status:'DISPATCHED',row_version:1 }
      else if (request.method()==='GET') body = []
      await route.fulfill({ contentType:'application/json',body:JSON.stringify(body) })
    })
    await page.goto(base+'/tests/work-entry-ui.html')
    await page.getByRole('button',{name:'布草车与工作车整理',exact:true}).click()
    await page.getByLabel('补充说明（选填）').waitFor()
    assert.equal(await page.getByLabel('是否有异常').count(),0)
    assert.equal(await page.getByLabel('工作结果',{exact:true}).count(),0)
    assert.equal(await page.getByLabel('退房打扫数').count(),0)
    await page.screenshot({ path:path.join(output,name+'-cart.png'),fullPage:true })
    await page.getByRole('button',{name:'关闭',exact:true}).last().click()
    for (let i=0;i<2;i++) {
      await page.getByRole('button',{name:'客诉报备',exact:true}).click()
      await page.getByText('发生时登记',{exact:true}).waitFor()
      await page.getByLabel('房号',{exact:false}).fill('120'+i)
      await page.getByLabel('客诉内容',{exact:false}).fill('模拟报备'+i)
      if(i===0) await page.screenshot({path:path.join(output,name+'-complaint.png'),fullPage:true})
      await page.getByRole('button',{name:'上传并提交',exact:true}).click()
      await page.getByText('已提交 '+(i+1)+' 条',{exact:true}).waitFor()
    }
    assert.equal(submissions.length,2)
    assert.notEqual(submissions[0].payload.roomNumber,submissions[1].payload.roomNumber)
    assert.equal(submissions.some(r => 'issueFound' in r.payload || 'workSummary' in r.payload),false)
    await page.getByRole('button',{name:'遗留物品移交',exact:true}).click()
    await page.getByLabel('遗留物接收人').selectOption('recipient')
    assert.equal(await page.getByLabel('是否发现遗留物').count(),0)
    await page.getByRole('button',{name:'上传并提交',exact:true}).click()
    await page.getByText('请完成表单中的所有必填项。',{exact:true}).waitFor()
    await page.screenshot({path:path.join(output,name+'-lost-property.png'),fullPage:true})
    await page.getByRole('button',{name:'关闭',exact:true}).last().click()
    await page.getByRole('button',{name:'工作下达',exact:true}).click()
    await page.getByPlaceholder('输入姓名、岗位或部门，如：前厅主管').fill('前厅主管')
    await page.getByLabel(/^执行负责人/).selectOption('fo-supervisor')
    assert.equal(await page.getByLabel(/^执行负责人/).locator('option').count(),2)
    await page.getByRole('button',{name:'刷新人员',exact:true}).click()
    await page.getByLabel(/^执行负责人/).waitFor()
    assert.ok(refreshes>=2)
    await page.getByLabel('任务标题',{exact:true}).fill('验证任务')
    await page.screenshot({path:path.join(output,name+'-dispatch.png'),fullPage:true})
    await page.getByRole('button',{name:'创建并下达',exact:true}).click()
    await page.getByText('已提交 3 条',{exact:true}).waitFor()
    assert.deepEqual(errors,[])
    result.push({viewport:name,submissions:submissions.length,taskTargetRefreshes:refreshes,pageErrors:errors.length})
    await context.close()
  }
  console.log(JSON.stringify(result))
} finally { await browser.close() }

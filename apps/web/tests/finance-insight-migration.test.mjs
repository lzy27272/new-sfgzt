import { test } from 'node:test'
import assert from 'node:assert/strict'
import { currentFinanceMonth, insightValue, insightEditContent, validateColumnMapping } from '../src/features/finance/insightModel.ts'
test('missing financial source is not numeric zero', () => { assert.equal(insightValue(null), '资料不足，暂不可判断'); assert.equal(insightValue('0.00'), '0.00') })
test('month uses business browser local date, not UTC conversion', () => assert.equal(currentFinanceMonth(new Date(2026, 9, 1, 0, 1)), '2026-10'))
test('editing analysis preserves citations and uncertainty and cannot rewrite source facts', () => { const result = insightEditContent({ analysis: [{ text: '旧', citations: ['book:revenue'] }], recommendations: [{ text: '旧建议', citations: ['book:revenue'], uncertainty: '待财务核实' }] }, ['新分析'], ['新建议']); assert.deepEqual(result.analysis[0].citations, ['book:revenue']); assert.equal(result.recommendations[0].uncertainty, '待财务核实'); assert.equal(result.facts, undefined) })
test('migration requires explicit mapping to actual headers', () => { assert.equal(validateColumnMapping({ accountCode: '科目', debit: '借方' }, ['accountCode', 'debit'], ['科目', '借方']), true); assert.equal(validateColumnMapping({ accountCode: '科目' }, ['accountCode', 'credit'], ['科目']), false); assert.equal(validateColumnMapping({ accountCode: '不存在' }, ['accountCode'], ['科目']), false) })

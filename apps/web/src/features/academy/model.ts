import { apiRequest, type ApiIdentity } from '../../api/client'
export const request = <T,>(identity: ApiIdentity, path: string, method = 'GET', body?: unknown, signal?:AbortSignal) =>
  apiRequest<T>('/academy' + path, identity, { method, signal, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
export const message = (error: unknown) => error instanceof Error ? error.message : '操作失败，请重试'
export const labels: Record<string,string> = { ACTIVE:'进行中', COMPLETED:'已完成', SUSPENDED:'已暂停', CANCELLED:'已取消', DRAFT:'草稿', READY:'待考试', PUBLISHED:'已发布', RETIRED:'旧版本', LEARNING:'学习中', EXAM:'考试中', RUNNING:'答题中', RETRY:'待补考', PASSED:'已通过', FAILED:'未通过', NEEDS_ATTENTION:'待安排补考', CONFIG_ERROR:'待完善题库', INTERN:'实习员工', REGULAR:'正式员工', SECONDMENT:'第三方委派（兼职）', SINGLE:'单选题', MULTIPLE:'多选题', BOOLEAN:'判断题', EASY:'简单', NORMAL:'一般', HARD:'较难' }
export const dateTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
export const clock = (value: number) => Math.floor(Math.max(0,value)/60).toString().padStart(2,'0') + ':' + Math.floor(Math.max(0,value)%60).toString().padStart(2,'0')
export type Course = { id:string; title:string; description:string; kind:'VIDEO'|'PPT'; state:string; version:number; revision:number; knowledge_tags:string[]; page_count?:number; page_seconds:number[]; duration_seconds?:number; processing_note?:string; watched_seconds?:number; completed_pages?:number; completed_at?:string }
export type Question = { id?:string; course_id:string; kind:string; stem:string; options:string[]; answers:number[]; explanation:string; knowledge:string; difficulty:string; active:boolean; version:number }
export type Rule = { kind:string; count:number; points:number; difficulty?:string; knowledge?:string; courseIds?:string[] }
export type Stage = { id:string; stage_no:number; title:string; objective:string; courseIds:string[]; rules:Rule[]; pass_score:number; exam_minutes:number; attempt_limit:number; retry_minutes:number; due_days?:number; progress_state?:string; due_at?:string; available_at?:string; last_error?:string; courses:Course[]; exams:Exam[] }
export type GrowthMap = { id:string; title:string; state:string; version:number; revision:number; position_id:string; scope_org_id?:string; position_name?:string; org_name?:string; stages:Stage[] }
export type Plan = { id:string; state:string; current_stage:number; map_title:string; position_name:string; org_name:string; stage_count:number; passed_stages:number; stages:Stage[]; employee_name?:string; name?:string; employee_type?:string; stage_title?:string; progress_state?:string; due_at?:string; best_score?:number; exam_count?:number }
export type PaperQuestion = { id:string; kind:string; stem:string; options:string[]; points:number; answers?:number[]; explanation?:string }
export type Exam = { id:string; plan_id:string; state:string; attempt_no:number; score?:number; total_score:number; pass_score:number; minutes:number; version:number; questionCount:number; stageTitle:string; attemptLimit:number; deadline?:string; serverTime:string; questions:PaperQuestion[]; answers:Record<string,number[]>; availableAt?:string }
export type Catalogue = { courses:Course[]; questions:Question[]; maps:GrowthMap[]; positions:{id:string; name:string}[]; orgs:{id:string; name:string}[] }
export type Mine = { plans:Plan[]; issues:{reason:string}[]; employeeType:string }
export type Team = { plans:Plan[]; issues:{employee_name?:string; reason:string; org_name?:string}[] }
export type LearningSession = { sessionId:string; sequence:number; position:number; page:number; course:Course; watched_seconds:number; completed_pages:number; page_elapsed:number[]; completed_at?:string }
export type Progress = { sequence:number; watchedSeconds:number; completedPages:number; pageElapsed:number[]; completed:boolean }

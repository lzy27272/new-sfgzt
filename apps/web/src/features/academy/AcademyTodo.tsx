import { useEffect,useState } from 'react'
import type { RoleContext } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { request,type Mine,labels } from './model'
export function AcademyTodo({identity,go}:{identity:RoleContext;go:AppNavigate}){
  const [data,setData]=useState<Mine>();const [error,setError]=useState(false)
  useEffect(()=>{let active=true;void request<Mine>(identity,'/me').then(d=>{if(active)setData(d)}).catch(()=>{if(active)setError(true)});return()=>{active=false}},[identity])
  if(!error&&(!data||(!data.plans.some(p=>p.state==='ACTIVE')&&!data.issues.length)))return null
  return <article className="wb-panel"><header><h2>学院 · 我的成长</h2><button className="text-action" onClick={()=>go('academy',{tab:'mine'})}>全部学习计划 ›</button></header>
    {error?<p className="wb-empty">学习进度暂未读到，可进入学院重试。</p>:<>{data!.plans.filter(p=>p.state==='ACTIVE').map(p=><button className="wb-compact-row" key={p.id} onClick={()=>go('academy',{tab:'mine',id:p.id})}><span>{p.map_title}<small>{p.position_name} · 第 {p.current_stage} 阶段 · {p.passed_stages} / {p.stage_count} 阶段通过</small></span><b>{labels[p.progress_state||'LEARNING']} ›</b></button>)}{data!.issues.map((issue,n)=><p className="wb-empty" key={n}>{issue.reason}</p>)}</>}
  </article>
}

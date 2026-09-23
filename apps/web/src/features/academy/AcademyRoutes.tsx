import { lazy, Suspense } from 'react'
import type { ApiIdentity } from '../../api/client'
import type { RouteParams } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { MyLearning, TeamLearning } from './LearningPlans'
import './academy.css'
const AcademyAdmin = lazy(() => import('./AcademyAdmin'))
const CoursePlayer = lazy(() => import('./CoursePlayer'))
const Examination = lazy(() => import('./Examination'))
export default function AcademyRoutes({identity,permissions,params,go}:{identity:ApiIdentity;permissions:string[];params:RouteParams;go:AppNavigate}){
  const can=(permission:string)=>permissions.includes('*')||permissions.includes(permission)
  const tabs=[can('academy.learn')&&['mine','我的学习'],(can('academy.team-read')||can('academy.manage'))&&['team','团队学习'],can('academy.manage')&&['maps','成长地图'],can('academy.manage')&&['courses','课程库'],can('academy.manage')&&['questions','题库']].filter(Boolean) as string[][]
  const tab=tabs.some(x=>x[0]===params.tab)?params.tab:tabs[0]?.[0]
  const navigate=(next:Record<string,string>)=>go('academy',next)
  if(!tab)return <p>当前岗位未开放学院权限。</p>
  return <div className={'academy'+(tab==='mine'&&(params.course||params.exam)?' academy-focus':'')}><header className="ac-heading"><div><span className="ac-eyebrow">SIFANGGUAN ACADEMY</span><h2>学院中心</h2><p>每一步学习，都有清晰的成长方向</p></div><span className="ac-badge">学习 · 实践 · 成长</span></header>
    <nav className="ac-tabs" aria-label="学院中心">{tabs.map(([key,label])=><button key={key} aria-current={tab===key?'page':undefined} onClick={()=>navigate({tab:key})}>{label}</button>)}</nav>
    <Suspense fallback={<p className="ac-empty">正在打开学院…</p>}>
      {tab==='mine'?(params.exam?<Examination key={params.exam} identity={identity} id={params.exam} onBack={()=>navigate({tab:'mine',id:params.id||''})}/>:params.course&&params.id?<CoursePlayer key={params.course+params.id} identity={identity} planId={params.id} courseId={params.course} onBack={()=>navigate({tab:'mine',id:params.id!})}/>:<MyLearning identity={identity} planId={params.id} navigate={navigate}/>):tab==='team'?<TeamLearning identity={identity} canManage={can('academy.manage')}/>:<AcademyAdmin key={tab} identity={identity} tab={tab}/>}
    </Suspense></div>
}

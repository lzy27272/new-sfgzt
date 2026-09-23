import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError,type ApiIdentity } from '../../api/client'
import { request,message,labels,clock,type Exam } from './model'
export default function Examination({identity,id,onBack}:{identity:ApiIdentity;id:string;onBack:()=>void}){
  const [exam,setExam]=useState<Exam>();const [answers,setAnswers]=useState<Record<string,number[]>>({});const [index,setIndex]=useState(0);const [error,setError]=useState('');const [busy,setBusy]=useState(false);const [saved,setSaved]=useState('');const [remaining,setRemaining]=useState(0);const [confirm,setConfirm]=useState(false)
  const current=useRef<Exam|undefined>(undefined);const responses=useRef<Record<string,number[]>>({});const chain=useRef(Promise.resolve());const offset=useRef(0);const alive=useRef(true);const dirty=useRef(false);const closing=useRef(false);const conflicted=useRef(false)
  const accept=useCallback((data:Exam)=>{current.current=data;offset.current=Date.parse(data.serverTime)-Date.now();if(alive.current){setExam(data);setRemaining(data.deadline?Math.max(0,Math.ceil((Date.parse(data.deadline)-Date.parse(data.serverTime))/1000)):0)}},[])
  useEffect(()=>{alive.current=true;void request<Exam>(identity,'/exams/'+id).then(data=>{if(alive.current){accept(data);responses.current=data.answers||{};setAnswers(responses.current)}}).catch(e=>setError(message(e)));return()=>{alive.current=false}},[identity,id,accept])
  const save=useCallback((submit=false)=>{
    const snapshot=structuredClone(responses.current)
    chain.current=chain.current.then(async()=>{
      const item=current.current;if(!item||item.state!=='RUNNING'||conflicted.current)return
      try{
        const data=await request<Exam>(identity,'/exams/'+id+(submit?'/submit':'/answers'),submit?'POST':'PUT',{answers:snapshot,version:item.version})
        accept(data);dirty.current=JSON.stringify(snapshot)!==JSON.stringify(responses.current);if(alive.current){setSaved(submit?'已交卷':'答案已保存');setError('')}
      }catch(e){dirty.current=true;conflicted.current=e instanceof ApiError&&e.status===409;if(alive.current)setError(message(e));throw e}
    }).catch(()=>{})
    return chain.current
  },[identity,id,accept])
  useEffect(()=>{
    if(exam?.state!=='RUNNING')return
    const timer=window.setInterval(()=>{
      const left=Math.max(0,Math.ceil((Date.parse(current.current?.deadline||'')-Date.now()-offset.current)/1000));setRemaining(left)
      if(dirty.current&&!closing.current&&!conflicted.current){dirty.current=false;void save()}
      if(left===0&&!closing.current&&!conflicted.current){closing.current=true;setBusy(true);void save(true).finally(()=>{setBusy(false);closing.current=false})}
    },1000)
    return()=>window.clearInterval(timer)
  },[exam?.state,save])
  const reload=async()=>{setBusy(true);try{const data=await request<Exam>(identity,'/exams/'+id);accept(data);responses.current=data.answers||{};setAnswers(responses.current);dirty.current=false;conflicted.current=false;setError('');setSaved('已读取保存的答案')}catch(e){setError(message(e))}finally{setBusy(false)}}
  const start=async()=>{setBusy(true);try{const data=await request<Exam>(identity,'/exams/'+id+'/start','POST');accept(data);responses.current=data.answers||{};setAnswers(responses.current)}catch(e){setError(message(e))}finally{setBusy(false)}}
  const submit=async()=>{closing.current=true;setBusy(true);setConfirm(false);await save(true);setBusy(false);closing.current=false}
  const choose=(questionId:string,option:number,multiple:boolean)=>{const old=responses.current[questionId]||[];const selected=multiple?(old.includes(option)?old.filter(v=>v!==option):[...old,option]):[option];responses.current={...responses.current,[questionId]:selected};setAnswers(responses.current);dirty.current=true;setSaved('正在保存…')}
  const answered=Object.values(answers).filter(a=>a.length).length
  const back=async()=>{if(exam?.state==='RUNNING')await save();if(!dirty.current)onBack()}
  const question=exam?.questions[index]
  return <><button className="ac-link" onClick={()=>void back()}>‹ 返回成长地图</button>{error&&<p role="alert" className="ac-error">{error} <button disabled={busy} onClick={()=>void (conflicted.current||!exam?reload():save())}>{conflicted.current||!exam?'重新读取试卷':'重试保存'}</button></p>}{!exam&&!error&&<p className="ac-empty">正在读取考试…</p>}
    {exam?.state==='READY'&&<section className="ac-exam-intro"><span className="ac-eyebrow">阶段考核</span><h2>{exam.stageTitle}</h2><p>课程已学完，检验这一阶段的学习成果</p><div className="ac-metrics"><div><span>答题时长</span><strong>{exam.minutes}<small>分钟</small></strong></div><div><span>达标分数</span><strong>{exam.pass_score}<small> / {exam.total_score}</small></strong></div><div><span>试卷题量</span><strong>{exam.questionCount}<small>题</small></strong></div></div><p>第 {exam.attempt_no} / {exam.attemptLimit} 次机会 · 点击开始后计时，退出页面仍继续计时。</p><p>到时自动交卷，以服务端已保存的答案计分。</p><button className="primary" disabled={busy} onClick={()=>void start()}>开始考试</button></section>}
    {exam?.state==='RUNNING'&&<section className="ac-exam"><div className="ac-toolbar"><h3>{exam.stageTitle}</h3><strong className={remaining<60?'ac-error':''}>剩余 {clock(remaining)}</strong></div><div className="ac-exam-progress"><span>已答 {answered} / {exam.questionCount}</span><span role="status">{saved||'答案自动保存'}</span></div><progress max={exam.questionCount} value={answered}/>{question&&<div className="ac-question"><span className="ac-badge">{labels[question.kind]} · {question.points} 分</span><h3>{index+1}. {question.stem}</h3><div className="ac-options">{question.options.map((option,n)=><label className={(answers[question.id]||[]).includes(n)?'selected':''} key={n}><input disabled={busy} type={question.kind==='MULTIPLE'?'checkbox':'radio'} name={question.id} checked={(answers[question.id]||[]).includes(n)} onChange={()=>choose(question.id,n,question.kind==='MULTIPLE')}/><span>{String.fromCharCode(65+n)}. {option}</span></label>)}</div></div>}<div className="ac-toolbar"><button disabled={index===0} onClick={()=>setIndex(i=>i-1)}>上一题</button><button disabled={index>=exam.questions.length-1} onClick={()=>setIndex(i=>i+1)}>下一题</button><button className="primary" disabled={busy} onClick={()=>setConfirm(true)}>提交试卷</button></div><nav className="ac-question-numbers" aria-label="答题卡">{exam.questions.map((q,n)=><button aria-current={n===index?'step':undefined} className={answers[q.id]?.length?'answered':''} key={q.id} onClick={()=>setIndex(n)}>{n+1}</button>)}</nav></section>}
    {exam&&['PASSED','FAILED'].includes(exam.state)&&<section className="ac-exam-intro"><span className={'ac-result-symbol '+(exam.state==='PASSED'?'passed':'')}>{exam.state==='PASSED'?'✓':'↻'}</span><h2>{exam.state==='PASSED'?'阶段考核通过':'继续复习，再接再厉'}</h2><strong className="ac-score">{exam.score}<small> / {exam.total_score} 分</small></strong><p>达标线 {exam.pass_score} 分 · 第 {exam.attempt_no} 次考核</p><p>{exam.state==='PASSED'?'成长地图已更新，可以查看下一阶段。':'请返回课程复习，补考开放时间与剩余机会可在成长地图查看。'}</p><button className="primary" onClick={onBack}>返回成长地图</button>{exam.state==='PASSED'&&<div className="ac-review">{exam.questions.map((q,n)=><section className="ac-panel" key={q.id}><h4>{n+1}. {q.stem}</h4><p>正确答案：{q.answers?.map(i=>q.options[i]).join('；')}</p><p>你的答案：{exam.answers[q.id]?.map(i=>q.options[i]).join('；')||'未作答'}</p>{q.explanation&&<p>解析：{q.explanation}</p>}</section>)}</div>}</section>}
    {confirm&&<div className="ac-modal"><section role="dialog" aria-modal="true" aria-label="确认交卷"><h3>确认提交试卷</h3><p>{answered<exam!.questionCount?'还有 '+(exam!.questionCount-answered)+' 道题未作答。':'所有题目已作答。'}交卷后无法修改。</p><div className="ac-toolbar"><button onClick={()=>setConfirm(false)}>继续答题</button><button className="primary" disabled={busy} onClick={()=>void submit()}>确认交卷</button></div></section></div>}
  </>
}

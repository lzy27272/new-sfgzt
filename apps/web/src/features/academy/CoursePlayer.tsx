import { useCallback, useEffect, useRef, useState } from 'react'
import { apiBlob, type ApiIdentity } from '../../api/client'
import { request,message,clock,type LearningSession,type Progress } from './model'
export default function CoursePlayer({identity,planId,courseId,onBack}:{identity:ApiIdentity;planId:string;courseId:string;onBack:()=>void}){
  const [session,setSession]=useState<LearningSession>();const [progress,setProgress]=useState<Progress>();const [url,setUrl]=useState('');const [page,setPage]=useState(1);const [position,setPosition]=useState(0);const [playing,setPlaying]=useState(false);const [starting,setStarting]=useState(false);const [error,setError]=useState('');const [retry,setRetry]=useState(0)
  const video=useRef<HTMLVideoElement>(null);const lease=useRef<LearningSession|undefined>(undefined);const saved=useRef<Progress|undefined>(undefined);const currentPage=useRef(1);const pending=useRef<Promise<void>|undefined>(undefined);const visible=useRef(true);const alive=useRef(false);const fault=useRef(false);const pageReady=useRef(false)
  const opening=useRef<{key:string;promise:Promise<LearningSession>}|undefined>(undefined)
  const heartbeatRequest=useRef<AbortController|undefined>(undefined)
  const beat=useCallback((playIntent?:boolean):Promise<void>=>{
    const item=lease.current;if(!item||fault.current)return Promise.resolve()
    if(pending.current)return pending.current
    const run=async()=>{
      const controller=new AbortController();heartbeatRequest.current=controller
      const media=video.current;const front=saved.current?.watchedSeconds||0
      const isVisible=document.visibilityState==='visible'
      try{
        const data=await request<Progress>(identity,'/sessions/'+item.sessionId+'/heartbeat','POST',{
          sequence:item.sequence+1,position:isVisible?(media?.currentTime||0):front,page:currentPage.current,
          playing:playIntent??(!!media&&!media.paused&&!media.ended),visible:isVisible&&(item.course.kind==='VIDEO'||pageReady.current),playbackRate:media?.playbackRate||1
        },controller.signal)
        if(alive.current&&lease.current===item){item.sequence=data.sequence;saved.current=data;setProgress(data)}
      }catch(e){if(!controller.signal.aborted&&alive.current&&lease.current===item){fault.current=true;media?.pause();setPlaying(false);setError(message(e))}}
      finally{if(heartbeatRequest.current===controller)heartbeatRequest.current=undefined}
    }
    const task=run();pending.current=task
    void task.finally(()=>{if(pending.current===task)pending.current=undefined})
    return task
  },[identity])
  const startPlayback=async()=>{
    if(starting||!video.current)return
    setStarting(true)
    try{
      // A pending paused heartbeat must finish before arming playback on the server.
      if(pending.current)await pending.current
      await beat(true)
      if(alive.current&&!fault.current&&document.visibilityState==='visible')await video.current?.play()
    }catch(e){setError(message(e))}finally{if(alive.current)setStarting(false)}
  }
  useEffect(()=>{
    alive.current=true;fault.current=false;lease.current=undefined;setSession(undefined);setError('');setUrl('');pageReady.current=false
    let active=true
    // Reuse the request across StrictMode's effect replay: a second start revokes the first lease.
    const key=JSON.stringify([identity.tenantId,identity.actorId,planId,courseId,retry])
    if(opening.current?.key!==key)opening.current={key,promise:request<LearningSession>(identity,'/sessions','POST',{planId,courseId,position:0,page:1})}
    void opening.current.promise.then(data=>{
      if(!active)return
      lease.current=data;setSession(data)
      const next=Math.min(data.completed_pages+1,data.course.page_count||1);currentPage.current=next;setPage(next)
      const p={sequence:data.sequence,watchedSeconds:data.watched_seconds,completedPages:data.completed_pages,pageElapsed:data.page_elapsed,completed:!!data.completed_at};saved.current=p;setProgress(p)
    }).catch(e=>{if(active)setError(message(e))})
    return()=>{active=false;alive.current=false;lease.current=undefined;heartbeatRequest.current?.abort()}
  },[identity,planId,courseId,retry])
  useEffect(()=>{
    if(!session)return
    let active=true;let objectUrl='';setUrl('');pageReady.current=false
    const suffix=session.course.kind==='PPT'?'&page='+page:''
    void apiBlob('/academy/courses/'+courseId+'/media?planId='+planId+suffix,identity).then(blob=>{
      objectUrl=URL.createObjectURL(blob);if(active)setUrl(objectUrl);else URL.revokeObjectURL(objectUrl)
    }).catch(e=>{if(active)setError(message(e))})
    return()=>{active=false;if(objectUrl)URL.revokeObjectURL(objectUrl)}
  },[identity,planId,courseId,session,page])
  useEffect(()=>{
    if(!session)return
    const tick=window.setInterval(()=>void beat(),2000)
    const visibility=()=>{visible.current=document.visibilityState==='visible';if(!visible.current){video.current?.pause();if(video.current)video.current.currentTime=saved.current?.watchedSeconds||0;setPlaying(false)}void beat()}
    const leaving=()=>{fault.current=true;video.current?.pause();heartbeatRequest.current?.abort()}
    const restored=(event:PageTransitionEvent)=>{if(event.persisted)setRetry(value=>value+1)}
    document.addEventListener('visibilitychange',visibility);window.addEventListener('pagehide',leaving);window.addEventListener('pageshow',restored)
    return()=>{window.clearInterval(tick);document.removeEventListener('visibilitychange',visibility);window.removeEventListener('pagehide',leaving);window.removeEventListener('pageshow',restored)}
  },[session,beat])
  const changePage=async(next:number)=>{await beat();if(fault.current)return;pageReady.current=false;currentPage.current=next;setPage(next)}
  const course=session?.course;const frontier=progress?.watchedSeconds||0
  return <><button className="ac-link" onClick={()=>{video.current?.pause();onBack()}}>‹ 返回成长地图</button><div className="ac-toolbar"><h3>{course?.title||'正在准备课程…'}</h3><span className="ac-badge">{progress?.completed?'已完成':'必修课程'}</span></div>
    {error&&<p role="alert" className="ac-error">{error} <button onClick={()=>setRetry(x=>x+1)}>重新连接</button></p>}
    {course&&<section className="ac-player-panel">{!url&&<p className="ac-empty">正在加载课件…</p>}{course.kind==='VIDEO'?<>
      <video ref={video} src={url||undefined} playsInline preload="auto" disablePictureInPicture controlsList="nodownload noplaybackrate noremoteplayback" onContextMenu={e=>e.preventDefault()} onLoadedMetadata={()=>{const v=video.current;if(v){v.currentTime=frontier;setPosition(frontier)}}} onPlay={()=>{setPlaying(true);void beat()}} onPause={()=>{setPlaying(false);void beat()}} onEnded={()=>{setPlaying(false);void beat()}} onTimeUpdate={()=>setPosition(video.current?.currentTime||0)} onSeeking={()=>{const v=video.current;if(v&&v.currentTime>(saved.current?.watchedSeconds||0)+0.4)v.currentTime=saved.current?.watchedSeconds||0}} onRateChange={()=>{if(video.current)video.current.playbackRate=1}} onError={()=>{if(url)setError('浏览器无法播放此视频，请联系管理员检查 MP4 编码')}}/>
      <div className="ac-video-controls"><button disabled={!url||!!error||starting} onClick={()=>{if(!video.current)return;if(video.current.paused)void startPlayback();else video.current.pause()}}>{starting?'准备播放…':playing?'暂停':'播放'}</button><span>{clock(position)} / {clock(course.duration_seconds||0)}</span><span>1.0×</span></div>
      <label className="ac-replay">已看内容可回放<input type="range" aria-label="回放已看视频" min={0} max={frontier||0.01} step={0.1} value={Math.min(position,frontier)} onChange={e=>{if(video.current)video.current.currentTime=Number(e.target.value)}}/></label>
      <p className="ac-notice">按正常速度观看；尚未观看的部分会随学习进度开放。切换到后台时自动暂停。</p>
      <progress max={course.duration_seconds||1} value={frontier}/>
    </>:<>{url&&<img className="ac-slide" src={url} alt={'课件第 '+page+' 页'} onLoad={()=>{pageReady.current=true;void beat()}}/>}<div className="ac-slide-controls"><button disabled={page<=1||!!error} onClick={()=>void changePage(page-1)}>上一页</button><span>第 {page} / {course.page_count} 页</span><button disabled={page>=(course.page_count||1)||page>(progress?.completedPages||0)||!!error} onClick={()=>void changePage(page+1)}>下一页</button></div><p className="ac-notice">{page<=(progress?.completedPages||0)?'本页已学完，可继续下一页':'本页还需浏览 '+Math.ceil(Math.max(0,(course.page_seconds[page-1]||5)-(progress?.pageElapsed[page-1]||0)))+' 秒'}</p><progress max={course.page_count||1} value={progress?.completedPages||0}/></>}
    <footer className="ac-exam-strip"><span>{progress?.completed?'课程已完成，全部必修课完成后会自动开放考核。':'学习进度自动保存'}</span>{progress?.completed&&<button className="primary" onClick={onBack}>返回查看阶段考核</button>}</footer></section>}
  </>
}

// ===== Android native bridge (APK only) =====
const NAT=(typeof window.Android!=='undefined')?window.Android:null;
function natSync(){if(!NAT)return;let r;try{r=JSON.parse(NAT.sync())}catch(e){return}
let changed=false;const ackT=[],ackU=[];
(r.finished||[]).forEach(t=>{ackT.push(t.id);if(D.trips.some(x=>x.id===t.id))return;const v=(D.running&&D.running.v)||D.cur;const so=(D.running&&D.running.native&&D.running.startOdo)||maxOdo()||'';
D.trips.push({id:t.id,v:v,start:t.start,end:t.end,driveMs:t.driveMs,legs:t.legs||[],stops:t.stops||[],track:t.track||[],startOdo:so,endOdo:so?Math.round(+so+(+t.gpsKm||0)):'',gpsKm:t.gpsKm,kmSource:'GPS',km:t.gpsKm,use:t.use||'',purpose:'',notes:'',native:true});changed=true});
Object.keys(r.uses||{}).forEach(id=>{ackU.push(id);const t=D.trips.find(x=>x.id===id);if(t&&t.use!==r.uses[id]){t.use=r.uses[id];changed=true}});
const prev=JSON.stringify(D.running||null);
if(r.running){const o=r.running;o.v=(D.running&&D.running.native&&D.running.v)||D.cur;o.startOdo=(D.running&&D.running.native&&D.running.startOdo!==undefined)?D.running.startOdo:(maxOdo()||'');o.gps=true;o.native=true;o.gpsStatus=o.gpsStatus||'native GPS';D.running=o}
else if(D.running&&D.running.native)D.running=null;
if(prev!==JSON.stringify(D.running||null))changed=true;
if(changed){save();checkStore()}
if(ackT.length||ackU.length)NAT.ack(JSON.stringify({trips:ackT,uses:ackU}));
if(changed&&!$('#dlg').open)render();
const un=D.trips.filter(t=>t.native&&!t.use);if(un.length&&!$('#dlg').open)classify(un[0])}
function classify(t){form(`<h2>Trip ended: ${(+t.km).toFixed(1)} km</h2><p class="muted">${fmtD(t.start)} ${fmtT(t.start)}–${fmtT(t.end)} · ${dur(tDur(t))} driving · ${(t.stops||[]).length} stops</p>`+S('use','Personal or company use? *',['','Personal','Company'],'').replace('<select','<select required')+F('startOdo','Start odometer (km)',t.startOdo,'number','min=0')+F('endOdo','End odometer (km)',t.endOdo,'number','min=0')+F('km','Km driven',t.km,'number','step=0.1 min=0')+F('purpose','Purpose / destination',''),
f=>{t.use=f.use;t.purpose=f.purpose;t.startOdo=f.startOdo;t.endOdo=f.endOdo;t.km=finalKm(f,t.gpsKm)},null,()=>{});kmLive(t.gpsKm)}
if(NAT){
 startGPS=function(){};
 startTrip=function(){const r=NAT.cmd('start');toast({started:'Trip started',resumed:'Trip resumed',already:'Trip already running',waiting:'Finish the last trip first'}[r]||r);setTimeout(natSync,200);return true};
 endTrip=function(force){const r=NAT.cmd(force==='home'?'home':force==='stop'?'stop':'end');
  if(r==='unknown'){form('<h2>Are you home?</h2><p class="muted">No recent GPS fix or home not set.</p>',()=>{NAT.cmd('home');setTimeout(natSync,200)},null,()=>{NAT.cmd('stop');setTimeout(natSync,200)});$('#frm').querySelector('button[value=save]').textContent='Yes — end trip';$('#frm').querySelector('button[value=cancel]').textContent='No — just a stop';return}
  toast({closed:'Trip ended',paused:'Stop recorded — trip paused',none:'No trip running'}[r]||r);setTimeout(natSync,200)};
 const _saveHome=saveHome;saveHome=function(){_saveHome();NAT.setHome(JSON.stringify(D.home||null))};
 NAT.setHome(JSON.stringify(D.home||null));
 dl=function(n,t,m){toast(NAT.saveFile(n,m,t))};
 setInterval(natSync,3000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)natSync()});
 window.addEventListener('hashchange',natHash);
}
function natHash(){const h=location.hash||'';if(h.indexOf('#open-')===0){const id=h.slice(6);try{history.replaceState(null,'',location.pathname)}catch(e){}natSync();tab='trips';render();const t=D.trips.find(x=>x.id===id);if(t&&!$('#dlg').open){t.use?tripForm(t):classify(t)}}else if(h==='#tab-trips'||h==='#tab-set'){try{history.replaceState(null,'',location.pathname)}catch(e){}tab=h==='#tab-set'?'set':'trips';natSync();render()}}
window.natRender=function(){render()};
function androidCard(){if(!NAT)return'';let s={};try{s=JSON.parse(NAT.status())}catch(e){}let p={};try{p=JSON.parse(NAT.paired())}catch(e){}let car='';try{car=JSON.parse(NAT.sync()).car||''}catch(e){}
const ok=b=>b?'✅':'❌';let lg=[];try{lg=JSON.parse(NAT.log())}catch(e){}const run=D.running&&D.running.native?D.running:null;
const p2=n=>(n<10?'0':'')+n,ft=t=>{const d=new Date(t);return p2(d.getDate())+'/'+p2(d.getMonth()+1)+' '+p2(d.getHours())+':'+p2(d.getMinutes())+':'+p2(d.getSeconds())};
return`<div class="card"><h2>📱 Android: car Bluetooth & background GPS</h2><p class="muted">Pick your car radio. Connect = trip starts/resumes; disconnect = stop, or trip ends if you're within the home radius. GPS records in the background with a notification.</p>
${p.error?`<p class="due">${esc(p.error)}</p>`:`<label>Car Bluetooth device<select id="natCar"><option value="">— none (manual only) —</option>${(p.devices||[]).map(d=>`<option value="${esc(d.addr)}" ${d.addr===car?'selected':''}>${esc(d.name)}</option>`).join('')}</select></label>`}
${car?'':'<p class="due">⚠ No car picked: any Bluetooth audio/car device will start a trip. Pick your MU-X radio above.</p>'}<p style="font-size:14px">${ok(s.fine)} Location · ${ok(s.background)} Location all the time · ${ok(s.bt)} Bluetooth · ${ok(s.notif)} Notifications · ${ok(s.battery)} Battery unrestricted · ${ok(s.gpsOn)} Phone GPS on</p>
<p style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn" type="button" onclick="NAT.requestPerms()">Grant permissions</button><button class="btn2" type="button" onclick="NAT.battery()">Battery: unrestricted</button><button class="btn2" type="button" onclick="NAT.openSettings()">App settings</button></p></div>
<div class="card"><h2>🚗 Manual trip (GPS, screen-off OK)</h2><p class="muted">${run?(run.paused?'Trip paused at a stop':'Trip recording')+' · '+(+run.gpsKm||0).toFixed(1)+' km · '+(run.track||[]).length+' points · GPS: '+esc(run.gpsStatus||'waiting'):'No trip running.'} GPS fixes this session: ${s.fixes||0}</p>
<p style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn" type="button" id="natStart" onclick="startTrip()">${run&&run.paused?'▶ Resume trip':'▶ Start trip'}</button><button class="btn2" type="button" onclick="endTrip()" ${run?'':'disabled'}>⏹ End trip</button></p></div>
<div class="card"><h2>🩺 Diagnostics / event log</h2><p style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn2" type="button" onclick="NAT.shareLog()">Share log</button><button class="btn2" type="button" onclick="render()">Refresh</button></p>
<div style="font:12px/1.4 monospace;max-height:320px;overflow:auto;white-space:pre-wrap">${lg.length?lg.slice().reverse().map(e=>ft(e[0])+'  '+esc(e[1])).join('\n'):'No events yet.'}</div></div>`}

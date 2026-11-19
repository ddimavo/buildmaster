const {chromium}=require('playwright');
const names=process.argv.slice(2);
(async()=>{const b=await chromium.launch();
for(const n of names){const p=await b.newPage({viewport:{width:900,height:700},deviceScaleFactor:1.5});
await p.goto('file:///tmp/claude-0/proto/release/'+n+'.html');
const el=await p.$('.wrap');await el.screenshot({path:'/tmp/claude-0/proto/release/'+n+'.png'});await p.close();}
await b.close()})();

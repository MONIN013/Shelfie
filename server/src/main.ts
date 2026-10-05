import { openDatabase } from './database.ts';
import { createApp } from './app.ts';
process.umask(0o077);
const publicUrl=(process.env.PUBLIC_URL??'http://127.0.0.1:8787').replace(/\/$/,'');
const db=openDatabase(process.env.DATABASE_PATH??'./data/shelfie.sqlite');
const server=createApp(db,{publicUrl,trustProxy:process.env.TRUST_PROXY==='1'});
server.requestTimeout=20_000; server.headersTimeout=15_000; server.maxRequestsPerSocket=100;
server.listen(Number(process.env.PORT??8787),process.env.HOST??'127.0.0.1',()=>console.log('Shelfie 0.5 ready'));
for(const signal of ['SIGINT','SIGTERM']) process.on(signal,()=>server.close(()=>{db.close();process.exit(0);}));

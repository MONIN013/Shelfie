import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { randomBytes, randomUUID, scrypt as scryptCallback, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';
import type { DatabaseSync } from 'node:sqlite';
import { HttpError, check, object, text, parseShelf, publicShelf, type Book } from './model.ts';
import { bookSearch } from './books.ts';
const scrypt=promisify(scryptCallback);
const digest=(value:string)=>createHash('sha256').update(value).digest('hex');
function passwordValue(value:unknown):string {
  check(typeof value==='string'&&value.length>=12&&value.length<=128,'パスワードは12〜128文字にしてください。');
  return value;
}
async function passwordHash(password:string):Promise<string> {
  const salt=randomBytes(16).toString('hex');
  const key=await scrypt(password,salt,64) as Buffer;
  return `${salt}:${key.toString('hex')}`;
}
async function passwordMatches(password:string,stored:string):Promise<boolean> {
  const [salt,key]=stored.split(':');
  const actual=await scrypt(password,salt!,64) as Buffer;
  return timingSafeEqual(Buffer.from(key!,'hex'),actual);
}
interface User { id:string; username:string; display_name:string; password:string; }
interface Post { id:string; user_id:string; title:string; note:string; document:string; published_at:number; display_name:string; }
interface Draft { title:string; note:string; document:string; revision:number; }
interface Options { publicUrl:string; trustProxy?:boolean; search?:(query:string)=>Promise<Book[]>; }
async function body(req:IncomingMessage):Promise<Record<string,unknown>> {
  if(!req.headers['content-type']?.startsWith('application/json')) throw new HttpError(415,'JSON形式で送信してください。');
  const chunks:Buffer[]=[]; let size=0;
  for await(const chunk of req) { size+=chunk.length; if(size>1_048_576) throw new HttpError(413,'データが大きすぎます。'); chunks.push(chunk); }
  try { return object(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
  catch(error) { if(error instanceof HttpError) throw error; throw new HttpError(400,'JSONを読み込めません。'); }
}
function json(res:ServerResponse,status:number,data:unknown) {
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8'}); res.end(JSON.stringify(data));
}
export function createApp(db:DatabaseSync,options:Options) {
  const search=options.search??bookSearch();
  const limits=new Map<string,{count:number;until:number}>();
  function rate(key:string,max:number,window:number) {
    const now=Date.now(); const current=limits.get(key);
    if(current && current.until>now) { if(++current.count>max) throw new HttpError(429,'操作が続いています。しばらく待って再試行してください。'); }
    else { if(limits.size>10000) for(const [k,v] of limits) if(v.until<=now) limits.delete(k); if(limits.size>20000) throw new HttpError(503,'しばらく待って再試行してください。'); limits.set(key,{count:1,until:now+window}); }
  }
  function user(req:IncomingMessage):User {
    const token=req.headers.authorization?.match(/^Bearer ([a-f0-9]{64})$/)?.[1];
    if(!token) throw new HttpError(401,'ログインしてください。');
    const u=db.prepare('SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.hash=? AND s.expires_at>?').get(digest(token),Date.now()) as unknown as User|undefined;
    if(!u) throw new HttpError(401,'ログインの有効期限が切れました。もう一度ログインしてください。'); return u;
  }
  function session(u:User) {
    const token=randomBytes(32).toString('hex');
    db.prepare('DELETE FROM sessions WHERE expires_at<=?').run(Date.now());
    db.prepare('INSERT INTO sessions(hash,user_id,expires_at) VALUES(?,?,?)').run(digest(token),u.id,Date.now()+30*24*3600_000);
    db.prepare('DELETE FROM sessions WHERE user_id=? AND hash NOT IN (SELECT hash FROM sessions WHERE user_id=? ORDER BY expires_at DESC LIMIT 5)').run(u.id,u.id);
    return {token,user:{id:u.id,username:u.username,displayName:u.display_name}};
  }
  const view=(p:Post)=>({id:p.id,title:p.title,note:p.note,displayName:p.display_name,publishedAt:p.published_at,document:JSON.parse(p.document),url:`${options.publicUrl}/s/${p.id}`});
  const findPost=(id:string)=>db.prepare('SELECT p.*,u.display_name FROM posts p JOIN users u ON u.id=p.user_id WHERE p.id=?').get(id) as unknown as Post|undefined;
  return createServer(async(req,res)=>{
    res.setHeader('Cache-Control','no-store'); res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer'); res.setHeader('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    try {
      const url=new URL(req.url??'/', 'http://localhost'); const path=url.pathname; const method=req.method;
      const ip=options.trustProxy&&typeof req.headers['x-real-ip']==='string'?req.headers['x-real-ip']:req.socket.remoteAddress??'unknown';
      rate(`all:${ip}`,180,60_000);
      if(path==='/health'&&method==='GET') { db.prepare('SELECT 1').get(); return json(res,200,{status:'ok',version:'0.5.0'}); }
      if((path==='/v1/auth/register'||path==='/v1/auth/login')&&method==='POST') {
        rate(`auth:${ip}`,15,15*60_000);
        const b=await body(req); const username=text(b.username,32,3).toLowerCase();
        check(/^[a-z0-9_]{3,32}$/.test(username),'ユーザー名は半角英数字と_で3〜32文字です。');
        const submittedPassword=passwordValue(b.password);
        let u=db.prepare('SELECT * FROM users WHERE username=?').get(username) as unknown as User|undefined;
        if(path.endsWith('register')) {
          if(u) throw new HttpError(409,'そのユーザー名は使用されています。');
          const displayName=text(b.displayName,60,1); const password=await passwordHash(submittedPassword);
          // Hashing yields; uniqueness must also be enforced by SQLite for concurrent signups.
          try { db.prepare('INSERT INTO users(id,username,display_name,password,created_at) VALUES(?,?,?,?,?)').run(randomUUID(),username,displayName,password,Date.now()); }
          catch(error) { if(db.prepare('SELECT 1 FROM users WHERE username=?').get(username)) throw new HttpError(409,'そのユーザー名は使用されています。'); throw error; }
          u=db.prepare('SELECT * FROM users WHERE username=?').get(username) as unknown as User;
        } else {
          const fallback='00000000000000000000000000000000:'+ '00'.repeat(64);
          const valid=await passwordMatches(submittedPassword,u?.password??fallback);
          if(!valid||!u) throw new HttpError(401,'ユーザー名またはパスワードが違います。');
        }
        return json(res,200,session(u));
      }
      if(path==='/v1/books'&&method==='GET') {
        const books=await search(text(url.searchParams.get('q'),120,2));
        return json(res,200,{books,attribution:'openBD / Open Library',sourceUrl:'https://openbd.jp'});
      }
      if(path==='/v1/feed'&&method==='GET') {
        const query=text(url.searchParams.get('q')??'',100);
        const before=Number(url.searchParams.get('before')??Number.MAX_SAFE_INTEGER);
        const cursorId=text(url.searchParams.get('cursorId')??'~',80); check(Number.isSafeInteger(before)&&before>=0);
        const escaped=query.replace(/[\\%_]/g,'\\$&');
        const rows=db.prepare(`SELECT p.*,u.display_name FROM posts p JOIN users u ON p.user_id=u.id WHERE (p.published_at<? OR (p.published_at=? AND p.id<?)) AND (p.title LIKE ? ESCAPE '\\' OR p.document LIKE ? ESCAPE '\\') ORDER BY p.published_at DESC,p.id DESC LIMIT 16`).all(before,before,cursorId,`%${escaped}%`,`%${escaped}%`) as unknown as Post[];
        const page=rows.slice(0,15); const last=page.at(-1);
        return json(res,200,{posts:page.map(view),next:rows.length>15&&last?{before:last.published_at,cursorId:last.id}:null});
      }
      const postMatch=path.match(/^\/v1\/posts\/([a-f0-9-]{36})(?:\/(report))?$/);
      if(postMatch) {
        const p=findPost(postMatch[1]!); if(!p) throw new HttpError(404,'この棚は公開されていません。');
        if(!postMatch[2]&&method==='GET') {return json(res,200,view(p));}
        const u=user(req);
        if(postMatch[2]==='report'&&method==='POST') {
          const b=await body(req); rate(`report:${u.id}`,10,3600_000);
          db.prepare('INSERT OR IGNORE INTO reports VALUES(?,?,?,?,?)').run(randomUUID(),u.id,p.id,text(b.reason,1000,1),Date.now());
          return json(res,200,{ok:true});
        }
      }
      const publicMatch=path.match(/^\/s\/([a-f0-9-]{36})$/);
      if(publicMatch&&method==='GET') {
        const p=findPost(publicMatch[1]!); if(!p) throw new HttpError(404,'この棚は公開されていません。');
        const escape=(s:string)=>s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!));
        const shelf=parseShelf(JSON.parse(p.document)); const books=shelf.items.flatMap(i=>i.bookIds).map(id=>shelf.books.find(b=>b.id===id)!);
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});
        return res.end(`<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${escape(p.title)} · Shelfie</title><style>body{max-width:42rem;margin:3rem auto;padding:1.5rem;background:#faf7fc;color:#302b3a;font-family:system-ui}a{color:#6a5193}li{margin:1rem 0}</style><h1>${escape(p.title)}</h1><p>${escape(p.display_name)}</p><p>${escape(p.note)}</p><ul>${books.map(b=>`<li>${escape(b.title)} — ${escape(b.author)}</li>`).join('')}</ul><a href="shelfie://post/${p.id}">Shelfieで棚を見る</a><p>Shelfieでは、みんなの棚から次の一冊を探せます。</p></html>`);
      }
      if(path.startsWith('/v1/me')||path==='/v1/auth/logout') {
        const u=user(req);
        if(path==='/v1/me'&&method==='GET') return json(res,200,{id:u.id,username:u.username,displayName:u.display_name});
        if(path==='/v1/auth/logout'&&method==='POST') { db.prepare('DELETE FROM sessions WHERE hash=?').run(digest(req.headers.authorization!.slice(7))); return json(res,200,{ok:true}); }
        if(path==='/v1/me/password'&&method==='PUT') {
          rate(`password:${u.id}`,5,15*60_000); const b=await body(req);
          const current=passwordValue(b.currentPassword); const next=passwordValue(b.password);
          if(!await passwordMatches(current,u.password)) throw new HttpError(401,'現在のパスワードが違います。');
          const hash=await passwordHash(next);
          db.prepare('UPDATE users SET password=? WHERE id=?').run(hash,u.id);
          db.prepare('DELETE FROM sessions WHERE user_id=?').run(u.id); return json(res,200,session(u));
        }
        if(path==='/v1/me'&&method==='DELETE') {
          const b=await body(req); if(!await passwordMatches(passwordValue(b.password),u.password)) throw new HttpError(401,'パスワードが違います。');
          db.prepare('DELETE FROM users WHERE id=?').run(u.id); return json(res,200,{ok:true});
        }
        if(path==='/v1/me/shelf') {
          if(method==='GET') {
            const d=db.prepare('SELECT * FROM shelves WHERE user_id=?').get(u.id) as unknown as Draft|undefined;
            const p=db.prepare('SELECT id FROM posts WHERE user_id=?').get(u.id);
            return json(res,200,{shelf:d?{title:d.title,note:d.note,document:JSON.parse(d.document),revision:d.revision}:null,postId:p?.id??null});
          }
          if(method==='PUT') {
            const b=await body(req); const doc=parseShelf(b.document); const title=text(b.title,100,1); const note=text(b.note,1000);
            const d=db.prepare('SELECT revision FROM shelves WHERE user_id=?').get(u.id);
            if((d?.revision??0)!==b.revision) throw new HttpError(409,'別の端末で更新されています。クラウドの棚を確認してから保存してください。');
            const revision=Number(d?.revision??0)+1;
            db.prepare('INSERT INTO shelves VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET title=excluded.title,note=excluded.note,document=excluded.document,revision=excluded.revision').run(u.id,title,note,JSON.stringify(doc),revision);
            return json(res,200,{revision});
          }
        }
        if(path==='/v1/me/publication') {
          if(method==='DELETE') { db.prepare('DELETE FROM posts WHERE user_id=?').run(u.id); return json(res,200,{ok:true}); }
          if(method==='POST') {
            const b=await body(req); const d=db.prepare('SELECT * FROM shelves WHERE user_id=?').get(u.id) as unknown as Draft|undefined;
            if(!d||d.revision!==b.revision) throw new HttpError(409,'まず現在の棚をクラウドに保存してください。');
            const old=db.prepare('SELECT id FROM posts WHERE user_id=?').get(u.id); const id=String(old?.id??randomUUID());
            db.prepare('INSERT INTO posts VALUES(?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET title=excluded.title,note=excluded.note,document=excluded.document,published_at=excluded.published_at').run(id,u.id,d.title,d.note,JSON.stringify(publicShelf(parseShelf(JSON.parse(d.document)))),Date.now());
            return json(res,200,view(findPost(id)!));
          }
        }
      }
      throw new HttpError(404,'ページが見つかりません。');
    } catch(error) {
      if(res.headersSent) { res.end(); return; }
      if(error instanceof HttpError) { if(error.status===429) res.setHeader('Retry-After','60'); json(res,error.status,{error:error.message}); }
      else { console.error('Request failed',error instanceof Error?error.name:'UnknownError'); json(res,500,{error:'処理できませんでした。時間をおいて再試行してください。'}); }
    }
  });
}

import {test, type TestContext} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {openDatabase} from '../src/database.ts';
import {createApp} from '../src/app.ts';
import {parseShelf,parseBook} from '../src/model.ts';

const book={id:'manual-example',title:'本当の本',author:'著者',widthMm:128,heightMm:188,pageCount:240,sourceUrl:null,coverUrl:null};
const unused={...book,id:'manual-private',title:'棚から外した本'};
const shelf={schemaVersion:4,shelf:{widthMm:360,heightMm:270,depthMm:270},theme:'lavender',books:[book,unused],items:[{id:'one',bookIds:[book.id],propId:null,row:0,x:0,orientation:'SPINE'}]};
async function fixture(t: TestContext) {
  const directory=mkdtempSync(join(tmpdir(),'shelfie-test-')); const path=join(directory,'db.sqlite');
  const db=openDatabase(path); const server=createApp(db,{publicUrl:'https://example.com/shelfie',search:async()=>[book]});
  await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));
  const address=server.address(); assert(address&&typeof address==='object');
  const port=address.port;
  t.after(async()=>{await new Promise<void>(resolve=>server.close(()=>resolve())); db.close();rmSync(directory,{recursive:true,force:true});});
  async function request(path:string,method='GET',data?:unknown,token?:string) {
    const res=await fetch(`http://127.0.0.1:${port}${path}`,{method,headers:{...(token?{Authorization:`Bearer ${token}`}:{ }),...(data?{'Content-Type':'application/json'}:{})},body:data?JSON.stringify(data):undefined});
    return {status:res.status,data:await res.json()};
  }
  async function register(username='reader') {
    const r=await request('/v1/auth/register','POST',{username,displayName:'読者',password:'a-test-password-12345'}); assert.equal(r.status,200);return r.data.token as string;
  }
  async function publish(token:string) {
    const saved=await request('/v1/me/shelf','PUT',{title:'私の棚',note:'好きな本',document:shelf,revision:0},token);assert.equal(saved.status,200);
    const post=await request('/v1/me/publication','POST',{revision:1},token);assert.equal(post.status,200);return post.data.id as string;
  }
  return {request,register,publish,db,path};
}
test('accounts hash passwords and tokens; login, expiry and logout work',async t=>{
  const {request,register,db}=await fixture(t);const token=await register();
  assert.equal((await request('/v1/me','GET',undefined,token)).status,200);
  const stored=db.prepare('SELECT password FROM users').get()!;assert(!String(stored.password).includes('a-test-password'));
  assert.notEqual(db.prepare('SELECT hash FROM sessions').get()!.hash,token);
  assert.equal((await request('/v1/auth/login','POST',{username:'reader',password:'wrong-password-12345'})).status,401);
  const login=await request('/v1/auth/login','POST',{username:'reader',password:'a-test-password-12345'});assert.equal(login.status,200);
  await request('/v1/auth/logout','POST',{},token);assert.equal((await request('/v1/me','GET',undefined,token)).status,401);
  db.prepare('UPDATE sessions SET expires_at=0').run();assert.equal((await request('/v1/me','GET',undefined,login.data.token)).status,401);
});
test('draft is private, publish strips unused books, unpublish removes discovery and URLs',async t=>{
  const {request,register,publish}=await fixture(t); const token=await register();
  assert.equal((await request('/v1/me/shelf')).status,401);assert.deepEqual((await request('/v1/feed')).data.posts,[]);
  const id=await publish(token);const post=await request(`/v1/posts/${id}`);assert.equal(post.status,200);assert.deepEqual(post.data.document.books,[book]);
  assert.equal((await request('/v1/feed')).data.posts.length,1);
  await request('/v1/me/publication','DELETE',undefined,token);
  assert.equal((await request(`/v1/posts/${id}`)).status,404);assert.equal((await request(`/s/${id}`)).status,404);
  assert.deepEqual((await request('/v1/feed')).data.posts,[]);assert.equal((await request('/v1/me/shelf','GET',undefined,token)).data.shelf.document.books.length,2);
});
test('stale writes and stale publication cannot replace a newer draft',async t=>{
  const {request,register,publish}=await fixture(t); const token=await register();await publish(token);
  const payload={title:'新しい棚',note:'',document:shelf,revision:0};
  assert.equal((await request('/v1/me/shelf','PUT',payload,token)).status,409);
  assert.equal((await request('/v1/me/publication','POST',{revision:0},token)).status,409);
  assert.equal((await request('/v1/me/shelf','PUT',{...payload,revision:1},token)).status,200);
  assert.equal((await request('/v1/feed')).data.posts[0].title,'私の棚');
});
test('another account has its own draft and cannot impersonate an owner',async t=>{
  const {request,register,publish}=await fixture(t);const a=await register();const b=await register('visitor');await publish(a);
  assert.equal((await request('/v1/me/shelf','GET',undefined,b)).data.shelf,null);
  await request('/v1/me/publication','DELETE',undefined,b);assert.equal((await request('/v1/feed')).data.posts.length,1);
});
test('reading lists and favorites are device data, not account endpoints',async t=>{
  const {request,register,publish}=await fixture(t);const token=await register();const id=await publish(token);
  assert.equal((await request(`/v1/posts/${id}/books`,'POST',{bookId:book.id},token)).status,404);
  assert.equal((await request(`/v1/posts/${id}/favorite`,'POST',{},token)).status,404);
  assert.equal((await request('/v1/me/reading','GET',undefined,token)).status,404);
  assert.equal((await request('/v1/me/favorites','GET',undefined,token)).status,404);
});
test('report is authenticated, idempotent and never public',async t=>{
  const {request,register,publish,db}=await fixture(t);const token=await register();const id=await publish(token);
  assert.equal((await request(`/v1/posts/${id}/report`,'POST',{reason:'不適切'})).status,401);
  await request(`/v1/posts/${id}/report`,'POST',{reason:'不適切'},token);await request(`/v1/posts/${id}/report`,'POST',{reason:'不適切'},token);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM reports').get()!.n,1);assert(!JSON.stringify((await request('/v1/feed')).data).includes('不適切'));
});

test('password changes and account deletion preserve leading and trailing spaces',async t=>{
  const {request}=await fixture(t);const password='  a-test-password-12345  ';
  const registered=await request('/v1/auth/register','POST',{username:'spaces',displayName:'読者',password});assert.equal(registered.status,200);
  const changed=await request('/v1/me/password','PUT',{currentPassword:password,password:password+' '},registered.data.token);assert.equal(changed.status,200);
  assert.equal((await request('/v1/me','GET',undefined,registered.data.token)).status,401);
  assert.equal((await request('/v1/auth/login','POST',{username:'spaces',password:password+' '})).status,200);
  assert.equal((await request('/v1/me','DELETE',{password:password+' '},changed.data.token)).status,200);
});
test('pagination orders tied timestamps and query escapes SQL wildcard characters',async t=>{
  const {request,register,db}=await fixture(t);await register();
  const owner=String(db.prepare('SELECT id FROM users').get()!.id);
  db.prepare('INSERT INTO posts VALUES(?,?,?,?,?,?)').run('00000000-0000-0000-0000-000000000000',owner,'100% 好き','',JSON.stringify(shelf),500);
  assert.equal((await request('/v1/feed?q=%25')).data.posts.length,1);assert.equal((await request('/v1/feed?before=500&cursorId=00000000-0000-0000-0000-000000000000')).data.posts.length,0);
});
test('SQLite persists shelf and custom library across database reopen',async t=>{
  const {register,publish,path}=await fixture(t);const token=await register();await publish(token);
  const reopened=openDatabase(path);try{const row=reopened.prepare('SELECT document FROM shelves').get()!;assert.equal(JSON.parse(String(row.document)).books.length,2);}finally{reopened.close();}
});
test('account deletion cascades publication, sessions and private state',async t=>{
  const {request,register,publish}=await fixture(t);const token=await register();await publish(token);
  assert.equal((await request('/v1/me','DELETE',{password:'wrong-password-12345'},token)).status,401);
  assert.equal((await request('/v1/me','DELETE',{password:'a-test-password-12345'},token)).status,200);
  assert.equal((await request('/v1/me','GET',undefined,token)).status,401);assert.deepEqual((await request('/v1/feed')).data.posts,[]);
});
test('book lookup maps source errors without inventing successful results',async t=>{
  const {request}=await fixture(t);assert.equal((await request('/v1/books?q=book')).data.books[0].title,book.title);assert.equal((await request('/v1/books?q=x')).status,400);
});
test('untrusted documents drop unknown fields and reject unknown books and invalid geometry',()=>{
  assert.throws(()=>parseShelf({...shelf,items:[{...shelf.items[0],x:2}]}));
  assert.throws(()=>parseShelf({...shelf,items:[{...shelf.items[0],bookIds:['missing']}]}));
  assert.throws(()=>parseBook({...book,coverUrl:'http://127.0.0.1/admin'}));
  assert.throws(()=>parseBook({...book,pageCount:-1}));
  assert.equal('assetPath' in parseBook({...book,assetPath:'../../secret'}),false);
  assert.throws(()=>parseBook({...book,automaticThicknessMm:20,automaticThicknessSource:'photo'}));
  assert.equal(parseShelf(shelf).items.length,1);
});

test('custom shelf and measured book dimensions survive cloud publication',async t=>{
  const {request,register}=await fixture(t);const token=await register();
  const measured={...book,measuredThicknessMm:24};
  const document={...shelf,schemaVersion:4,theme:'oak',shelf:{widthMm:900,heightMm:400,depthMm:300},books:[measured],items:[{...shelf.items[0],x:4}]};
  assert.equal((await request('/v1/me/shelf','PUT',{title:'実寸の棚',note:'',document,revision:0},token)).status,200);
  const publication=await request('/v1/me/publication','POST',{revision:1},token);
  assert.equal(publication.status,200);assert.deepEqual(publication.data.document,document);
  assert.equal((await request('/v1/feed')).data.posts.length,1);
  const tooNarrow={...document,shelf:{widthMm:360,heightMm:400,depthMm:300}};
  assert.equal((await request('/v1/me/shelf','PUT',{title:'実寸の棚',note:'',document:tooNarrow,revision:1},token)).status,400);
  assert.deepEqual((await request('/v1/me/shelf','GET',undefined,token)).data.shelf.document,document);
});

test('photo regions are validated, kept in the private draft and never published',async t=>{
  const {request,register}=await fixture(t);const token=await register();
  const region={image:'a'.repeat(64)+'.jpg',corners:[0,0,1,0,1,1,0,1]};
  assert.throws(()=>parseBook({...book,spineRegion:{...region,image:'../secret.jpg'}}));
  assert.throws(()=>parseBook({...book,spineRegion:{...region,corners:[0,0,1,1,1,0,0,1]}}));
  const document={...shelf,books:[{...book,spineRegion:region},unused]};
  assert.equal((await request('/v1/me/shelf','PUT',{title:'写真の棚',note:'',document,revision:0},token)).status,200);
  assert.deepEqual((await request('/v1/me/shelf','GET',undefined,token)).data.shelf.document.books[0].spineRegion,region);
  const publication=await request('/v1/me/publication','POST',{revision:1},token);
  assert.deepEqual(publication.data.document.books,[book]);
});

test('only current shelf schema with explicit dimensions can be saved',async t=>{
  const {request,register}=await fixture(t);const token=await register();
  for(const version of [undefined,1,2,3,99]) {
    const document={...shelf,schemaVersion:version};
    assert.throws(()=>parseShelf(document));
    assert.equal((await request('/v1/me/shelf','PUT',{title:'棚',note:'',document,revision:0},token)).status,400);
  }
  assert.throws(()=>parseShelf({...shelf,shelf:undefined}));
  assert.throws(()=>parseShelf({...shelf,shelf:null}));
  assert.equal((await request('/v1/me/shelf','GET',undefined,token)).data.shelf,null);
});

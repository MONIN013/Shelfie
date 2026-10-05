import {test} from 'node:test';
import assert from 'node:assert/strict';
import {bookSearch} from '../src/books.ts';
import {HttpError} from '../src/model.ts';

test('search uses bounded fields, cache, safe source URLs and measurement defaults',async()=>{
  let calls=0;
  const mock:typeof fetch=async input=>{
    calls++;const url=new URL(String(input));assert.equal(url.hostname,'openlibrary.org');assert.equal(url.searchParams.get('limit'),'20');
    return Response.json({docs:[{key:'/works/OL1W',title:'本',author_name:['著者'],cover_i:123},{key:'https://evil.test',title:'不正'}]});
  };
  const search=bookSearch(mock);const first=await search('Title');
  assert.equal(first.length,1);assert.equal(first[0]?.pageCount,240);assert.equal(first[0]?.sourceUrl,'https://openlibrary.org/works/OL1W');assert.equal(first[0]?.coverUrl,'https://covers.openlibrary.org/b/id/123-M.jpg');
  assert.deepEqual(await search('title'),first);assert.equal(calls,1);
  await assert.rejects(search('Different'),e=>e instanceof HttpError&&e.status===429);
});
test('upstream errors remain errors and can use manual entry instead of fake results',async()=>{
  const search=bookSearch(async()=>new Response('',{status:503}));
  await assert.rejects(search('book'),e=>e instanceof HttpError&&e.status===502);
});

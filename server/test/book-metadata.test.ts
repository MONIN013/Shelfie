import {test} from 'node:test';
import assert from 'node:assert/strict';
import {isbnFromQuery,fromOpenBd,fromEdition} from '../src/book-metadata.ts';
import {parseBook} from '../src/model.ts';
import {bookSearch} from '../src/books.ts';

test('ISBN checksums, hyphens and ISBN-10 normalization',()=>{
  assert.equal(isbnFromQuery('ISBN: 978-4-10-101013-7'),'9784101010137');
  assert.equal(isbnFromQuery('0140328726'),'9780140328721');
  assert.equal(isbnFromQuery('9784101010138'),undefined);
  assert.equal(isbnFromQuery('title'),undefined);
});
const domestic={summary:{isbn:'9784101010137',title:'こころ',author:'夏目漱石',series:'新潮文庫',cover:'https://cover.openbd.jp/9784101010137.jpg'},onix:{DescriptiveDetail:{
  Measure:[{MeasureType:'01',Measurement:'15.1',MeasureUnitCode:'cm'},{MeasureType:'02',Measurement:'106',MeasureUnitCode:'mm'},{MeasureType:'03',Measurement:'0.5',MeasureUnitCode:'in'}],
  Extent:[{ExtentType:'11',ExtentValue:'336',ExtentUnit:'03'}]}}};
test('structured measurements retain units and provenance, invalid values use estimates',()=>{
  const b=parseBook(fromOpenBd(domestic,'9784101010137'));
  assert.equal(b.widthMm,106);assert.equal(b.heightMm,151);assert.equal(b.automaticThicknessMm,12.7);
  assert.equal(b.automaticThicknessSource,'bibliography');assert.equal(b.measuredThicknessMm,undefined);assert.equal(b.pageCount,336);
  const sparse=fromOpenBd({summary:domestic.summary},'9784101010137')!;
  assert.equal(sparse.widthMm,105);assert.equal(sparse.heightMm,148);assert.equal(sparse.pageCountEstimated,true);
  assert.equal(fromOpenBd(domestic,'9780140328721'),undefined);
  assert.throws(()=>parseBook({...b,automaticThicknessMm:-1}));
  assert.throws(()=>parseBook({...b,automaticThicknessSource:null}));
});
test('exact ISBN edition has its own cover, dimensions and page count',()=>{
  const b=fromEdition({key:'/books/OL1M',title:'版',number_of_pages:128,covers:[9],physical_dimensions:'8 x 5 x 0.5 inches'},'9780140328721')!;
  assert.equal(b.automaticThicknessMm,12.7);assert.equal(b.heightMm,203);assert.equal(b.widthMm,127);
  assert.equal(b.coverUrl,'https://covers.openlibrary.org/b/id/9-M.jpg');
  assert.equal(fromEdition({key:'/books/OL1M',title:'不明',physical_dimensions:'8 x 5 x .5'},'9780140328721')?.automaticThicknessMm,undefined);
});
test('ISBN lookup uses the domestic edition without an unnecessary OL work request',async()=>{
  let calls=0;
  const search=bookSearch(async input=>{calls++;assert.equal(String(input),'https://api.openbd.jp/v1/get?isbn=9784101010137');return Response.json([domestic]);});
  const result=await search('978-4-10-101013-7');assert.equal(result[0]?.pageCount,336);assert.equal(calls,1);
});

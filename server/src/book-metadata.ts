import type {Book} from './model.ts';

const record=(v:unknown):Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v)?v as Record<string,unknown>:{};
const list=(v:unknown):unknown[]=>Array.isArray(v)?v:v==null?[]:[v];
const string=(v:unknown):string=>typeof v==='string'?v:'';
const number=(v:unknown,min:number,max:number):number|undefined=>{
  if(typeof v!=='number'&&typeof v!=='string'||v==='')return;
  const n=Number(v);return Number.isFinite(n)&&n>=min&&n<=max?n:undefined;
};

/** Reject mistyped ISBNs before sending them upstream; normalize ISBN-10 to 13. */
export function isbnFromQuery(query:string):string|undefined {
  let isbn=query.replace(/^ISBN\s*:?[\s-]*/i,'').replace(/[\s-]/g,'').toUpperCase();
  if(/^\d{9}[\dX]$/.test(isbn)) {
    if([...isbn].reduce((n,c,i)=>n+(c==='X'?10:Number(c))*(10-i),0)%11!==0)return;
    isbn='978'+isbn.slice(0,9);
    isbn+=String((10-[...isbn].reduce((n,c,i)=>n+Number(c)*(i%2?3:1),0)%10)%10);
  }
  if(!/^97[89]\d{10}$/.test(isbn)||[...isbn].reduce((n,c,i)=>n+Number(c)*(i%2?3:1),0)%10!==0)return;
  return isbn;
}

/** ONIX list 48: 01 height, 02 width, 03 spine; list 50: mm/cm/in. */
export function fromOpenBd(value:unknown,isbn:string):Book|undefined {
  const entry=record(value),summary=record(entry.summary),onix=record(entry.onix);
  if(summary.isbn!==isbn||!string(summary.title).trim())return;
  const detail=record(onix.DescriptiveDetail),hanmoto=record(entry.hanmoto);
  const measures=new Map<string,number>();
  for(const raw of list(detail.Measure)) {
    const m=record(raw),unit=string(m.MeasureUnitCode),factor=({mm:1,cm:10,in:25.4} as Record<string,number>)[unit];
    const n=number(m.Measurement,.01,1000);if(factor&&n)measures.set(string(m.MeasureType),n*factor);
  }
  const format=string(hanmoto.hankeidokuji)+' '+string(summary.series);
  const fallback=/文庫/.test(format)?[105,148]:/B5|Ｂ５/.test(format)?[182,257]:/A5|Ａ５/.test(format)?[148,210]:[128,188];
  const pages=list(detail.Extent).map(record).filter(e=>e.ExtentUnit==='03'&&['00','11'].includes(string(e.ExtentType)))
    .map(e=>number(e.ExtentValue,1,3000)).find(p=>p!=null);
  const thickness=number(measures.get('03'),1,150);
  const cover=string(summary.cover);
  return {id:`manual-isbn-${isbn}`,isbn,title:string(summary.title).slice(0,200),author:string(summary.author).slice(0,200),
    widthMm:Math.round(number(measures.get('02'),50,400)??fallback[0]!),heightMm:Math.round(number(measures.get('01'),50,500)??fallback[1]!),
    pageCount:Math.round(pages??240),...(!pages?{pageCountEstimated:true}:{}),sourceUrl:`https://www.hanmoto.com/bd/isbn/${isbn}`,
    coverUrl:/^https:\/\/cover\.openbd\.jp\/\d{13}\.jpg$/.test(cover)?cover:null,
    ...(thickness?{automaticThicknessMm:Math.round(thickness*10)/10,automaticThicknessSource:'bibliography' as const}:{})};
}

export function fromEdition(value:unknown,isbn:string):Book|undefined {
  const e=record(value),key=string(e.key),title=string(e.title);
  if(!/^\/books\/OL\d+M$/.test(key)||!title.trim())return;
  // Accept only an unambiguous H x W x T value with explicit units. Never sort axes.
  const m=string(e.physical_dimensions).match(/^\s*(\d+(?:\.\d+)?)\s*[x×]\s*(\d+(?:\.\d+)?)\s*[x×]\s*(\d*\.?\d+)\s*(mm|cm|in|inches|centimeters|millimeters)\s*$/i);
  const factor=m?(/^(in|inches)$/i.test(m[4]!)?25.4:/^(cm|centimeters)$/i.test(m[4]!)?10:1):1;
  const h=m?number(Number(m[1])*factor,50,500):undefined,w=m?number(Number(m[2])*factor,50,400):undefined,t=m?number(Number(m[3])*factor,1,150):undefined;
  const valid=h!=null&&w!=null&&t!=null&&t<Math.min(h,w);
  const pages=number(e.number_of_pages,1,3000),cover=list(e.covers).find(c=>typeof c==='number'&&Number.isSafeInteger(c)&&c>0);
  return {id:`ol-${key.split('/').at(-1)}`,isbn,title:title.slice(0,200),author:string(e.by_statement).slice(0,200),
    widthMm:valid?Math.round(w):128,heightMm:valid?Math.round(h):188,pageCount:Math.round(pages??240),...(!pages?{pageCountEstimated:true}:{}),
    sourceUrl:`https://openlibrary.org${key}`,coverUrl:cover?`https://covers.openlibrary.org/b/id/${cover}-M.jpg`:null,
    ...(valid?{automaticThicknessMm:Math.round(t*10)/10,automaticThicknessSource:'bibliography' as const}:{})};
}

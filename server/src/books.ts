import { HttpError, type Book } from './model.ts';
import {isbnFromQuery,fromOpenBd,fromEdition} from './book-metadata.ts';

/** Human-triggered queries only; shared 1 request/second gate and bounded cache. */
export function bookSearch(fetcher: typeof fetch = fetch) {
  const cache = new Map<string,{time:number;books:Book[]}>();
  let availableAt=0;
  return async (query:string):Promise<Book[]> => {
    const key=query.toLowerCase(); const cached=cache.get(key);
    if(cached && Date.now()-cached.time<3_600_000) return cached.books;
    if(Date.now()<availableAt) throw new HttpError(429,'検索が混み合っています。少し待って再試行してください。');
    availableAt=Date.now()+1100;
    const remember=(books:Book[])=>{if(cache.size>=200)cache.delete(cache.keys().next().value!);cache.set(key,{time:Date.now(),books});return books;};
    const isbn=isbnFromQuery(query);
    if(isbn) {
      let domestic: Book|undefined;
      // Domestic editions first. A missing record falls back to the exact OL ISBN edition.
      try {
        const r=await fetcher(`https://api.openbd.jp/v1/get?isbn=${isbn}`,{signal:AbortSignal.timeout(4000)});
        if(r.ok){const data:unknown=await r.json();domestic=fromOpenBd(Array.isArray(data)?data[0]:null,isbn);if(domestic&&!domestic.pageCountEstimated)return remember([domestic]);}
      } catch { /* The independent edition provider below remains available. */ }
      try {
        const r=await fetcher(`https://openlibrary.org/isbn/${isbn}.json`,{headers:{'User-Agent':process.env.OPEN_LIBRARY_USER_AGENT??'Shelfie/0.5'},signal:AbortSignal.timeout(14_000)});
        if(r.status===404)return remember(domestic?[domestic]:[]);
        if(!r.ok)throw new Error('upstream');
        const book=fromEdition(await r.json(),isbn);
        if(domestic) return remember([{...domestic,...(book&&!book.pageCountEstimated?{pageCount:book.pageCount,pageCountEstimated:false}:{})}]);
        return remember(book?[book]:[]);
      } catch {if(domestic)return remember([domestic]);throw new HttpError(502,'ISBNの書誌を取得できません。再試行するか、手入力で登録してください。');}
    }
    const url=new URL('https://openlibrary.org/search.json');
    url.search=new URLSearchParams({q:query,limit:'20',fields:'key,title,author_name,cover_i,number_of_pages_median',lang:'ja'}).toString();
    try {
      const response=await fetcher(url,{headers:{'User-Agent':process.env.OPEN_LIBRARY_USER_AGENT ?? 'Shelfie/0.3 (small-scale book discovery)'},signal:AbortSignal.timeout(18_000)});
      if(!response.ok) throw new Error('upstream');
      const data:unknown=await response.json();
      if(!data || typeof data!=='object' || !('docs' in data) || !Array.isArray(data.docs)) throw new Error('format');
      const books:Book[]=data.docs.flatMap((b:unknown) => {
        if(!b || typeof b!=='object') return [];
        const item=b as Record<string,unknown>;
        if(typeof item.key!=='string' || !/^\/works\/OL\d+W$/.test(item.key) || typeof item.title!=='string') return [];
        const estimated=typeof item.number_of_pages_median!=='number'||!Number.isFinite(item.number_of_pages_median)||item.number_of_pages_median<1||item.number_of_pages_median>3000;
        const pages=estimated?240:Math.round(Number(item.number_of_pages_median));
        return [{id:`ol-${item.key.split('/').at(-1)}`,title:item.title.slice(0,200),author:Array.isArray(item.author_name)?item.author_name.filter(v=>typeof v==='string').join(' / ').slice(0,200):'',widthMm:128,heightMm:188,pageCount:pages,...(estimated?{pageCountEstimated:true}:{}),sourceUrl:`https://openlibrary.org${item.key}`,coverUrl:typeof item.cover_i==='number'&&Number.isSafeInteger(item.cover_i)&&item.cover_i>0?`https://covers.openlibrary.org/b/id/${item.cover_i}-M.jpg`:null}];
      });
      if(cache.size>=200) cache.delete(cache.keys().next().value!);
      cache.set(key,{time:Date.now(),books}); return books;
    } catch { throw new HttpError(502,'書誌検索に接続できません。再試行するか、手入力で登録してください。'); }
  };
}

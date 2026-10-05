export class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) { super(message); this.status = status; }
}
export function check(condition: unknown, message = '入力を確認してください。'): asserts condition {
  if (!condition) throw new HttpError(400, message);
}
export function object(value: unknown): Record<string, unknown> {
  check(value !== null && typeof value === 'object' && !Array.isArray(value));
  return value as Record<string, unknown>;
}
export function text(value: unknown, max: number, min = 0): string {
  check(typeof value === 'string' && value.trim().length >= min && value.length <= max);
  check(!/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value));
  return value.trim();
}
function integer(value: unknown, min: number, max: number): number {
  check(typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max);
  return value;
}
export interface Book {
  id: string; title: string; author: string;
  widthMm: number; heightMm: number; pageCount: number;
  sourceUrl: string | null; coverUrl: string | null;
  measuredThicknessMm?: number;
  automaticThicknessMm?: number;
  automaticThicknessSource?: 'bibliography';
  isbn?: string;
  pageCountEstimated?: boolean;
}
export function parseBook(input: unknown): Book {
  const b = object(input);
  const id = text(b.id, 90, 1);
  check(/^(ol-|manual-)[A-Za-z0-9-]{1,80}$/.test(id));
  const sourceUrl = b.sourceUrl == null ? null : text(b.sourceUrl, 160);
  const coverUrl = b.coverUrl == null ? null : text(b.coverUrl, 160);
  check(sourceUrl === null || /^https:\/\/openlibrary\.org\/(works\/OL\d+W|books\/OL\d+M)$|^https:\/\/www\.hanmoto\.com\/bd\/isbn\/\d{13}$/.test(sourceUrl));
  check(coverUrl === null || /^https:\/\/covers\.openlibrary\.org\/b\/id\/\d+-M\.jpg$|^https:\/\/cover\.openbd\.jp\/\d{13}\.jpg$/.test(coverUrl));
  const measured = b.measuredThicknessMm;
  check(measured == null || (typeof measured === 'number' && Number.isFinite(measured) && measured >= 1 && measured <= 150));
  const automatic=b.automaticThicknessMm, basis=b.automaticThicknessSource;
  check(automatic==null || (typeof automatic==='number'&&Number.isFinite(automatic)&&automatic>=1&&automatic<=150));
  check((automatic==null)===(basis==null) && (basis==null||basis==='bibliography'));
  check(b.isbn==null || typeof b.isbn==='string'&&/^(\d{13}|\d{9}[\dX])$/.test(b.isbn));
  check(b.pageCountEstimated==null||typeof b.pageCountEstimated==='boolean');
  return { id, title: text(b.title, 200, 1), author: text(b.author, 200),
    widthMm: integer(b.widthMm, 50, 400), heightMm: integer(b.heightMm, 50, 500),
    pageCount: integer(b.pageCount, 1, 3000), sourceUrl, coverUrl,
    ...(measured == null ? {} : {measuredThicknessMm: measured as number}),
    ...(automatic==null?{}:{automaticThicknessMm:automatic as number,automaticThicknessSource:'bibliography' as const}),
    ...(b.isbn==null?{}:{isbn:b.isbn as string}),
    ...(b.pageCountEstimated?{pageCountEstimated:true}:{}) };
}
export interface Item { id: string; bookIds: string[]; propId: string | null; row: number; x: number; orientation: 'SPINE' | 'COVER' | 'FLAT'; }
export interface ShelfSpec { widthMm: number; heightMm: number; depthMm: number; }
export interface Shelf { schemaVersion: number; theme: string; items: Item[]; books: Book[]; shelf: ShelfSpec; }
export function parseShelf(input: unknown): Shelf {
  const d = object(input);
  check(d.schemaVersion === 4, '対応していない棚形式です。');
  check(d.theme === 'lavender' || d.theme === 'oak');
  const s=object(d.shelf);
  const spec={widthMm:integer(s.widthMm,240,1800),heightMm:integer(s.heightMm,120,800),depthMm:integer(s.depthMm,100,600)};
  check(Array.isArray(d.books ?? []));
  const rawBooks = (d.books ?? []) as unknown[];
  check(rawBooks.length <= 500);
  const books = rawBooks.map(parseBook);
  const library = new Map(books.map(b => [b.id,b]));
  check(new Set(books.map(b=>b.id)).size === books.length);
  check(Array.isArray(d.items) && d.items.length <= 100);
  const seenBooks = new Set<string>(); const seenItems = new Set<string>();
  const items: Item[] = d.items.map(value => {
    const i = object(value); const id = text(i.id, 160, 1);
    check(!seenItems.has(id)); seenItems.add(id);
    const propId = i.propId == null ? null : text(i.propId, 20);
    check(propId === null || ['pebble','vase','arch'].includes(propId));
    check(Array.isArray(i.bookIds));
    const bookIds = i.bookIds.map(v=>text(v,90,1));
    check(propId ? bookIds.length === 0 : bookIds.length > 0 && bookIds.length <= 100);
    for (const book of bookIds) { check(library.has(book) && !seenBooks.has(book)); seenBooks.add(book); }
    const orientation = i.orientation ?? 'SPINE';
    check(orientation === 'SPINE' || orientation === 'COVER' || orientation === 'FLAT');
    check(bookIds.length <= 1 || orientation === 'FLAT');
    check(typeof i.x === 'number' && Number.isFinite(i.x));
    return {id,propId,bookIds,orientation,row:integer(i.row,0,0),x:i.x};
  });
  check(items.filter(i=>i.propId).length<=6);
  const dimensions = (i:Item): [number,number,number] => {
    if(i.propId) return [.7,i.propId==='vase'?1.1:.7,.7];
    const bs=i.bookIds.map(id=>library.get(id)!); const b=bs[0]!;
    const thick=(v:Book)=>(v.measuredThicknessMm ?? v.automaticThicknessMm ?? (v.pageCount/2*.09+2.6))/100;
    return i.orientation==='SPINE'?[thick(b),b.heightMm/100,b.widthMm/100]:i.orientation==='COVER'?[b.widthMm/100,b.heightMm/100,thick(b)]:[Math.max(...bs.map(b=>b.widthMm))/100,bs.reduce((s,b)=>s+thick(b),0),Math.max(...bs.map(b=>b.heightMm))/100];
  };
  for(const item of items) {
    const [w,h,depth]=dimensions(item); const half=spec.widthMm/200;
    check(item.x-w/2>=-half-.0001 && item.x+w/2<=half+.0001 && h<=(spec.heightMm/100)+.0001 && depth<=(spec.depthMm/100)+.0001,'本が棚の寸法を超えています。');
    for(const other of items) if(other.id!==item.id && other.row===item.row) check(Math.abs(item.x-other.x)+.0001>=(w+dimensions(other)[0])/2,'本が重なっています。');
  }
  return {schemaVersion:4,theme:d.theme,items,books,shelf:spec};
}
export function publicShelf(shelf: Shelf): Shelf {
  const ids=new Set(shelf.items.flatMap(i=>i.bookIds));
  return {...shelf,books:shelf.books.filter(b=>ids.has(b.id))};
}

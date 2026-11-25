/** True when the text has every word typed into a filter, in any order. An empty filter lets everything through. */
export function hasWords(text: string, filter: string): boolean {
  const searchable = text.toLowerCase();
  return filter.toLowerCase().split(/\s+/).every((word) => searchable.includes(word));
}

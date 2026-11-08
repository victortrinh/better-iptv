/** Movie and show Title numbers are separate number spaces (TMDB's, which the Provider copies; ADR 0014), so a Title is keyed by kind and number. */
export type Kind = "movie" | "show";

export interface TitleKey {
  kind: Kind;
  /** The Title number, as the Provider files its Listings under it. Never looked up. */
  tmdbId: number;
}

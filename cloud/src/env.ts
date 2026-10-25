export interface Env {
  DB: D1Database;
  /** TMDB's API root, `https://api.themoviedb.org/3`. */
  TMDB_API: string;
  /** TMDB's API read access token, or its short API key. A secret: it lives only in the cloud, never in the APK. */
  TMDB_TOKEN: string;
}

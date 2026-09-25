// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.session

import kyo.*

/** Native session search: the pure-Scala transcript scan. Scala Native has
  * no bundled SQLite/FTS5 (and the build stays dependency-free), so this is
  * the same linear scan the JVM falls back to when its index is unavailable.
  */
object PlatformSearch:
  def search(store: SessionStore, query: String, limit: Int): String < Sync =
    store.scanSearch(query, limit)
end PlatformSearch

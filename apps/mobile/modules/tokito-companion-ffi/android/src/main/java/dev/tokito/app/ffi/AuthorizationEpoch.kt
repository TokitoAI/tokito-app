package dev.tokito.app.ffi

/** In-memory ownership only; credentials never leave the native process. */
internal class AuthorizationEpoch {
  internal class Lease(val base: String, val token: String, val owner: String)
  private var current: Lease? = null

  @Synchronized fun begin(base: String, token: String, owner: String): Lease =
    Lease(base, token, owner).also { current = it }

  @Synchronized fun requireOwner(base: String, token: String, owner: String): Lease =
    current?.takeIf { it.base == base && it.token == token && it.owner == owner }
      ?: throw IllegalStateException("Workspace startup was superseded")

  @Synchronized fun matching(base: String, token: String): Lease? =
    current?.takeIf { it.base == base && it.token == token }

  @Synchronized fun capture(): Lease? = current

  @Synchronized fun owns(lease: Lease?): Boolean = lease != null && current === lease

  @Synchronized fun invalidateIfOwned(lease: Lease?): Boolean {
    if (lease == null || current !== lease) return false
    current = null
    return true
  }

  @Synchronized fun invalidate() { current = null }
}

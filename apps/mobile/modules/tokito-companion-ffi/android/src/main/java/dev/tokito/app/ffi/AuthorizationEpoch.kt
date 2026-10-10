package dev.tokito.app.ffi

/** In-memory ownership only; credentials never leave the native process. */
internal class AuthorizationEpoch {
  internal class Lease(val base: String, val token: String)
  private var current: Lease? = null

  @Synchronized fun begin(base: String, token: String): Lease =
    Lease(base, token).also { current = it }

  @Synchronized fun matching(base: String, token: String): Lease? =
    current?.takeIf { it.base == base && it.token == token }

  @Synchronized fun capture(): Lease? = current

  @Synchronized fun owns(lease: Lease?): Boolean = lease != null && current === lease

  @Synchronized fun invalidate() { current = null }
}

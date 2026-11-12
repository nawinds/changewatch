package live.changewatch.identity

import live.changewatch.core.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class IdentityService(private val uow: UnitOfWork) {
    fun register(name: String): Credentials {
        if (name.isBlank() || name.length > 100) throw InvalidInput("Name must contain 1..100 characters")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(32))
        val account = Account(UUID.randomUUID(), name.trim())
        uow.transaction { it.accounts.create(account, hash(token)); account }
        return Credentials(account, token)
    }

    fun authenticate(token: String): Account {
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) throw Unauthenticated()
        return uow.transaction { it.accounts.byTokenHash(hash(token)) ?: throw Unauthenticated() }
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

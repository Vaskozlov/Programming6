package commands.server

import database.Organization
import database.DatabaseInterface
import network.client.udp.User

class AddCommand : ServerSideCommand() {
    override suspend fun executeImplementation(
        user: User?,
        database: DatabaseInterface,
        argument: Any?
    ): Result<Unit?> {
        database.add(argument as Organization)
        return Result.success(null)
    }
}

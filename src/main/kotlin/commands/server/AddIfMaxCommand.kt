package commands.server

import database.Organization
import database.DatabaseInterface
import exceptions.NotMaximumOrganizationException
import lib.ExecutionStatus
import network.client.udp.User

class AddIfMaxCommand : ServerSideCommand() {
    override suspend fun executeImplementation(
        user: User?,
        database: DatabaseInterface,
        argument: Any?
    ): Result<Unit?> {
        if (database.addIfMax(argument as Organization) == ExecutionStatus.FAILURE) {
            throw NotMaximumOrganizationException()
        }

        return Result.success(null)
    }
}

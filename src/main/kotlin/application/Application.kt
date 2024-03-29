package application

import database.Address
import database.DatabaseInterface
import database.Organization
import exceptions.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import lib.BufferedReaderWithQueueOfStreams
import lib.ExecutionStatus
import lib.IOHelper
import lib.Localization
import network.client.DatabaseCommand
import org.example.client.commands.Command
import server.AuthorizationInfo
import java.io.IOException
import java.io.InputStreamReader

class Application(
    private val authFile: String?,
    private val database: DatabaseInterface,
    dispatcher: CoroutineDispatcher
) {
    private val applicationScope = CoroutineScope(dispatcher)
    private val bufferedReaderWithQueueOfStreams: BufferedReaderWithQueueOfStreams = BufferedReaderWithQueueOfStreams(
        InputStreamReader(System.`in`)
    )

    private var running = false
    private var localNameToDatabaseCommand: HashMap<String, DatabaseCommand> = HashMap()

    private val databaseCommandToExecutor = mapOf(
        DatabaseCommand.HELP to Command
        { _, _ ->
            Result.success(Localization.get("message.help"))

        },
        DatabaseCommand.INFO to Command
        { oDatabase, _ ->
            Result.success(oDatabase.getInfo())
        },

        DatabaseCommand.SHOW to Command
        { oDatabase, argument ->
            when (argument) {
                null, "json" -> Result.success(oDatabase.toJson())
                "csv" -> Result.success(oDatabase.toCSV())
                else -> Result.failure(InvalidOutputFormatException())
            }
        },

        DatabaseCommand.ADD to Command
        { oDatabase, argument ->
            oDatabase.add(argument as Organization)
            Result.success(null)
        },

        DatabaseCommand.UPDATE to Command
        { oDatabase, argument ->
            oDatabase.modifyOrganization(argument as Organization)
            Result.success(null)
        },

        DatabaseCommand.REMOVE_BY_ID to Command
        { oDatabase, argument ->
            oDatabase.removeById(argument as Int)
                .takeIf { it == ExecutionStatus.SUCCESS }
                ?.let { Result.success(null) }
                ?: Result.failure<Organization>(OrganizationKeyException("$argument"))
        },

        DatabaseCommand.CLEAR to Command
        { oDatabase, _ ->
            oDatabase.clear()
            Result.success(null)
        },

        DatabaseCommand.SAVE to Command
        { oDatabase, argument ->
            oDatabase.save(argument as String)
                .await()
                .takeIf { it == ExecutionStatus.SUCCESS }
                ?.let { Result.success(null) }
                ?: Result.failure(FileWriteException())
        },

        DatabaseCommand.READ to Command
        { oDatabase, argument ->
            val filename = argument as String
            oDatabase.loadFromFile(filename)
                .takeIf { it == ExecutionStatus.SUCCESS }
                ?.let { Result.success(null) }
                ?: Result.failure(FileReadException(filename))
        },

        DatabaseCommand.EXECUTE_SCRIPT to Command
        { _, argument ->
            val filename = argument as String
            this.bufferedReaderWithQueueOfStreams
                .runCatching {
                    this.pushStream(filename)
                    Result.success(filename)
                }
                .onFailure { Result.failure<Command>(it) }
        },

        DatabaseCommand.EXIT to Command
        { _, _ ->
            this.stop()
            Result.success(null)
        },

        DatabaseCommand.REMOVE_HEAD to Command
        { oDatabase, _ ->
            oDatabase.removeHead()
                ?.let { Result.success(it) }
                ?: Result.failure(OrganizationNotFoundException())
        },

        DatabaseCommand.ADD_IF_MAX to Command
        { oDatabase, argument ->

            oDatabase.addIfMax(argument as Organization)
                .takeIf { it == ExecutionStatus.SUCCESS }
                ?.let { Result.success(null) }
                ?: Result.failure(NotMaximumOrganizationException())
        },

        DatabaseCommand.HISTORY to Command
        { oDatabase, _ ->
            Result.success(oDatabase.getHistory())
        },

        DatabaseCommand.MAX_BY_FULL_NAME to Command
        { oDatabase, _ ->
            oDatabase.maxByFullName()
                ?.let { Result.success(it) }
                ?: Result.failure(OrganizationNotFoundException())
        },

        DatabaseCommand.REMOVE_ALL_BY_POSTAL_ADDRESS to Command
        { oDatabase, argument ->
            oDatabase.removeAllByPostalAddress(argument as Address)
            Result.success(null)
        },

        DatabaseCommand.SUM_OF_ANNUAL_TURNOVER to Command
        { oDatabase, _ ->
            Result.success(oDatabase.getSumOfAnnualTurnover())
        }
    )

    private val argumentForCommand: Map<DatabaseCommand, (String?) -> Any?> = mapOf(
        DatabaseCommand.HELP to { null },
        DatabaseCommand.INFO to { null },
        DatabaseCommand.CLEAR to { null },
        DatabaseCommand.SAVE to { null },
        DatabaseCommand.EXIT to { null },
        DatabaseCommand.REMOVE_HEAD to { null },
        DatabaseCommand.HISTORY to { null },
        DatabaseCommand.MAX_BY_FULL_NAME to { null },
        DatabaseCommand.SUM_OF_ANNUAL_TURNOVER to { null },
        DatabaseCommand.SHOW to { it },
        DatabaseCommand.READ to { it },
        DatabaseCommand.EXECUTE_SCRIPT to { it },
        DatabaseCommand.REMOVE_BY_ID to { it?.toIntOrNull() },
        DatabaseCommand.ADD to {
            OrganizationBuilder.constructOrganization(
                bufferedReaderWithQueueOfStreams,
                false
            )
        },
        DatabaseCommand.UPDATE to {
            val org = OrganizationBuilder.constructOrganization(
                bufferedReaderWithQueueOfStreams,
                true
            )
            org.id = it!!.toInt()
            org
        },
        DatabaseCommand.REMOVE_ALL_BY_POSTAL_ADDRESS to {
            OrganizationBuilder.constructAddress(
                bufferedReaderWithQueueOfStreams,
                false
            )
        },
        DatabaseCommand.ADD_IF_MAX to {
            OrganizationBuilder.constructOrganization(
                bufferedReaderWithQueueOfStreams,
                false
            )
        }
    )

    private fun loadCommands() {
        localNameToDatabaseCommand.clear()

        for ((key, value) in commandNameToDatabaseCommand) {
            localNameToDatabaseCommand[Localization.get(key)] = value
        }
    }

    private fun localize() {
        Localization.askUserForALanguage(bufferedReaderWithQueueOfStreams)
        loadCommands()
    }

    private fun login() {
        val authorizationInfo: AuthorizationInfo

        if (authFile != null) {
            val fileContext = IOHelper.readFile(authFile) ?: throw IOException("Unable to read from file $authFile")
            authorizationInfo = Json.decodeFromString(fileContext)
        } else {
            print(Localization.get("message.ask.login"))
            val login = bufferedReaderWithQueueOfStreams.readLine()
            print(Localization.get("message.ask.password"))
            val password = bufferedReaderWithQueueOfStreams.readLine()
            authorizationInfo = AuthorizationInfo(login, password)
        }

        database.login(authorizationInfo)
    }

    fun start() = runBlocking {
        localize()
        login()
        println(Localization.get("message.introduction"))
        running = true

        while (running) {
            try {
                val line = bufferedReaderWithQueueOfStreams.readLine()
                processCommand(line.trim())
            } catch (error: Throwable) {
                println(exceptionToMessage(error))
            }
        }
    }

    fun stop() {
        running = false
    }

    private suspend fun processCommand(input: String) {
        val allArguments = splitInputIntoArguments(input)

        if (allArguments.isEmpty()) {
            return
        }

        val commandName = allArguments[0]
        val commandArgument = allArguments.getOrNull(1)
        val databaseCommand = localNameToDatabaseCommand[commandName]
        val argumentExecutor = argumentForCommand[databaseCommand]

        if (databaseCommand == null || argumentExecutor == null || !argumentForCommand.containsKey(databaseCommand)) {
            System.out.printf(Localization.get("message.command.not_found"), commandName)
            return
        }

        val executionArgument = argumentExecutor.invoke(commandArgument)

        if (databaseCommand == DatabaseCommand.EXECUTE_SCRIPT) {
            executeCommand(databaseCommand, executionArgument)
        } else {
            applicationScope.launch { executeCommand(databaseCommand, executionArgument) }
        }
    }

    private suspend fun executeCommand(databaseCommand: DatabaseCommand, argument: Any?) {
        val executor = databaseCommandToExecutor[databaseCommand]
        val result = executor!!.execute(database, argument)

        if (result.isSuccess) {
            val successMessage = commandSuccessMessage(databaseCommand, result.getOrNull())
            println(successMessage)
        } else {
            val exception = result.exceptionOrNull()
            val errorMessage = exceptionToMessage(exception)
            println(errorMessage)
        }
    }
}

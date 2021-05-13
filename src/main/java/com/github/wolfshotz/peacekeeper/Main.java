package com.github.wolfshotz.peacekeeper;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.event.domain.InteractionCreateEvent;
import discord4j.core.object.command.ApplicationCommandInteraction;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.User;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.rest.RestClient;
import discord4j.rest.http.client.ClientException;
import discord4j.rest.interaction.InteractionResponse;
import discord4j.rest.json.response.ErrorResponse;
import discord4j.rest.util.ApplicationCommandOptionType;
import discord4j.rest.util.Color;
import discord4j.rest.util.Permission;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import javax.naming.NoPermissionException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Main
{
    private static final Logger LOG = Loggers.getLogger(Main.class);

    public static void main(String[] args)
    {
        GatewayDiscordClient client = DiscordClient.create(System.getProperty("token")).login().block();

        refreshCommands(client);

        ExecutorService service = Executors.newSingleThreadExecutor();
        service.submit(() -> console(client));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> client.logout().block()));

        client.on(new ReactiveEventAdapter()
        {
            @Override
            public Publisher<?> onInteractionCreate(InteractionCreateEvent event)
            {
                switch (event.getCommandName())
                {
                    case "globalban":
                        return globalBan(client, event);
                    case "ping":
                        return event.acknowledge()
                                .then(event.getInteractionResponse()
                                        .createFollowupMessage("Pong!"));
                }

                return Mono.empty();
            }
        }).blockLast();

        client.onDisconnect().block();
    }

    private static void refreshCommands(GatewayDiscordClient client)
    {
        RestClient rest = client.getRestClient();
        long appId = rest.getApplicationId().block();

        ApplicationCommandRequest globalBan = ApplicationCommandRequest.builder()
                .name("globalban")
                .description("Ban a user across multiple servers.")
                .addOption(ApplicationCommandOptionData.builder()
                        .name("userid")
                        .description("The 18-digit unique identifier of the user")
                        .type(ApplicationCommandOptionType.STRING.getValue())
                        .required(true)
                        .build())
                .addOption(ApplicationCommandOptionData.builder()
                        .name("reason")
                        .description("A reason for the ban")
                        .type(ApplicationCommandOptionType.STRING.getValue())
                        .required(false)
                        .build())
                .build();

        ApplicationCommandRequest ping = ApplicationCommandRequest.builder()
                .name("ping")
                .description("pong!")
                .build();

        List<ApplicationCommandRequest> list = Arrays.asList(globalBan, ping);

        rest.getApplicationService()
                .bulkOverwriteGlobalApplicationCommand(appId, list)
                .doOnError(e -> LOG.error("Something happend registering a command...", e))
                .onErrorResume(e -> Mono.empty())
                .blockLast();

        LOG.info("Commands Initalized: {}", list.stream()
                .map(ApplicationCommandRequest::name)
                .collect(Collectors.toList()));
    }

    private static void console(GatewayDiscordClient client)
    {
        try
        {
            Scanner input = new Scanner(System.in);
            while (true)
            {
                while (input.hasNextLine())
                {
                    String command = input.nextLine();
                    switch (command)
                    {
                        case "stop":
                            LOG.info("Stopping...");
                            input.close();
                            System.exit(0);
                        case "refresh_commands":
                            refreshCommands(client);
                            break;
                    }
                }
                Thread.sleep(100);
            }
        }
        catch (Throwable error)
        {
            LOG.warn("Something happened while listening to console...");
            error.printStackTrace();
            System.exit(-1);
        }
    }

    // must return the response of the command
    private static Publisher<?> globalBan(GatewayDiscordClient client, InteractionCreateEvent event)
    {
        InteractionResponse response = event.getInteractionResponse();
        ApplicationCommandInteraction args = event.getInteraction().getCommandInteraction();
        long userId = args.getOption("userid")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asString)
                .map(Long::parseLong)
                .orElse(1L);
        String reason = args.getOption("reason")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asString)
                .orElse("<No Reason Specified>");

        Member executor = event.getInteraction().getMember().orElse(null);

        return event.acknowledge()
                .then(ensurePermissions(executor))
                .then(client.getUserById(Snowflake.of(userId)))
                .flatMap(user -> banUserIn(user, client.getGuilds(), executor, reason))
                .flatMap(user -> response.createFollowupMessage(String.format("User `%s` (ID: `%s`) has been banned across all servers for: \"%s\"", user.getTag(), user.getId().asString(), reason)))
                .onErrorResume(ClientException.class, e ->
                {
                    if (e.getErrorResponse().isPresent())
                    {
                        ErrorResponse er = e.getErrorResponse().get();
                        Object obj = er.getFields().get("message");
                        if (obj instanceof String && "Unknown User".equals(obj))
                            return response.createFollowupMessage("Are you gonna supply an ACTUAL user id?");
                    }

                    LOG.error("Unable to process ban", e);
                    return response.createFollowupMessage("SOMETHING went wrong when banning...");
                })
                .onErrorResume(NoPermissionException.class, e -> response.createFollowupMessage("You don't have permission to use this, buddy."));
    }

    private static Mono<Void> ensurePermissions(@Nullable Member member)
    {
        if (member != null)
            return member.getBasePermissions()
                    .flatMap(set -> set.contains(Permission.BAN_MEMBERS)? Mono.empty() : Mono.error(new NoPermissionException()));
        return Mono.error(new NoPermissionException());
    }

    private static Mono<User> banUserIn(User user, Flux<Guild> guilds, Member executor, String reason)
    {
        return guilds.flatMap(guild -> guild.ban(user.getId(), spec -> spec.setReason(reason))
                .flatMap(__ -> guild.getPublicUpdatesChannel()
                        .flatMap(channel -> channel.createEmbed(spec -> createBanEmbed(spec, user, executor, reason)))))
                .then(Mono.just(user));
    }

    private static void createBanEmbed(EmbedCreateSpec spec, User user, Member executor, String reason)
    {
        spec.setAuthor(executor.getUsername(), null, executor.getAvatarUrl())
                .setTitle("User `%s` (ID: `%s`) has been banned across all servers")
                .setThumbnail(user.getAvatarUrl())
                .setDescription("**Reason:** " + reason)
                .setColor(Color.RED)
                .setTimestamp(Instant.now());
    }

}

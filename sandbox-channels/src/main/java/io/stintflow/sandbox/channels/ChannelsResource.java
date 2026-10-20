package io.stintflow.sandbox.channels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.wire.Json;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * SDD S.5, 8g/8h: the channels' HTTP side.
 * <ul>
 *   <li>{@code GET /reviews}, {@code POST /reviews/{reviewId}}: the portal, with HTTP Basic and the test users of
 *       {@code channels.portal.users.*}. The role comes from the credential, never from the request.</li>
 *   <li>{@code POST /meetings} {@code {"meetingId"}}: the recorder, for a corpus meeting.</li>
 *   <li>{@code GET /dev/confluence/pages}: what the fake Confluence holds (ids, space, title, labels; the body only with
 *       {@code ?bodies=true}, for the PII sweeper), and
 *       {@code GET /dev/reviews}: every open review — for the scenarios, which watch from outside.</li>
 * </ul>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ChannelsResource {

    @Inject
    ChannelsService service;

    @ConfigProperty(name = "channels.portal.test-password")
    String testPassword;

    private final Map<String, String> roleOfUser = new HashMap<>();

    @PostConstruct
    void users() {
        String prefix = "channels.portal.users.";
        for (String name : ConfigProvider.getConfig().getPropertyNames()) {
            if (name.startsWith(prefix)) {
                roleOfUser.put(name.substring(prefix.length()),
                        ConfigProvider.getConfig().getValue(name, String.class));
            }
        }
    }

    record Person(String user, String role) {
    }

    /** The person behind an HTTP Basic header, if the test credential matches. */
    Optional<Person> authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String user = decoded.substring(0, colon);
        String role = roleOfUser.get(user);
        boolean passwordOk = MessageDigest.isEqual(decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8),
                testPassword.getBytes(StandardCharsets.UTF_8));
        return role != null && passwordOk ? Optional.of(new Person(user, role)) : Optional.empty();
    }

    @GET
    @Path("reviews")
    public Response reviews(@HeaderParam("Authorization") String authorization) {
        Optional<Person> person = authenticate(authorization);
        if (person.isEmpty()) {
            return unauthorized();
        }
        ArrayNode out = Json.MAPPER.createArrayNode();
        service.portal().openFor(person.get().role()).forEach(out::add);
        return Response.ok(out.toString()).build();
    }

    @POST
    @Path("reviews/{reviewId: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response answer(@HeaderParam("Authorization") String authorization, @PathParam("reviewId") String reviewId,
            String body) {
        Optional<Person> person = authenticate(authorization);
        if (person.isEmpty()) {
            return unauthorized();
        }
        JsonNode answer;
        try {
            answer = Json.read(body.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Response.status(422).entity("{\"error\":\"not JSON\"}").build();
        }
        return switch (service.portal().answer(person.get().user(), person.get().role(), reviewId, answer)) {
            case PUBLISHED -> Response.accepted("{}").build();
            case NOT_OPEN_FOR_ROLE -> Response.status(403).entity("{\"error\":\"not open for your role\"}").build();
            case OUT_OF_CONTRACT -> Response.status(422).entity("{\"error\":\"out of the review contract\"}").build();
        };
    }

    @POST
    @Path("meetings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(String body) throws Exception {
        String meetingId = Json.read(body.getBytes(StandardCharsets.UTF_8)).path("meetingId").asText();
        Optional<Corpus.Meeting> meeting = service.recorder().corpus().find(meetingId);
        if (meeting.isEmpty()) {
            return Response.status(404).entity("{\"error\":\"no such meeting in the corpus\"}").build();
        }
        var event = service.recorder().record(meeting.get());
        return Response.accepted(Json.obj().put("eventId", event.getId()).put("meetingId", meetingId).toString())
                .build();
    }

    @GET
    @Path("dev/confluence/pages")
    public String pages(@jakarta.ws.rs.QueryParam("bodies") boolean bodies) {
        ArrayNode out = Json.MAPPER.createArrayNode();
        service.confluence().pages().forEach(p -> {
            var page = out.addObject().put("id", p.id()).put("space", p.spaceKey()).put("title", p.title());
            page.set("labels", Json.MAPPER.valueToTree(p.labels()));
            if (bodies) {
                page.put("body", p.body());   // the sweeper reads it (S.6); synthetic data, a dev endpoint
            }
        });
        return out.toString();
    }

    @GET
    @Path("dev/reviews")
    public String allReviews() {
        return service.portal().all().toString();
    }

    private static Response unauthorized() {
        return Response.status(401).header("WWW-Authenticate", "Basic realm=\"sandbox-portal (test users only)\"")
                .entity("{\"error\":\"test credentials required\"}").build();
    }
}

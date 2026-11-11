package com.dps.teknokent.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.security.institution.OtgLoginProvider;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FirebaseBearerFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String REGISTER_PATH_PREFIX = "/api/auth/register";
	private static final String ONBOARDING_PATH_PREFIX = "/api/onboarding/";
	private static final String STATUS_PATH = "/api/auth/me";
	private static final String LANGUAGE_PATH = "/api/auth/me/language";
	private static final String MENTOR_PROFILE_PATH_PREFIX = "/api/mentors/";
	private static final String AVAILABILITY_SETTINGS_SUFFIX = "/availability-settings";
	private static final String USER_PROFILE_PATH_PREFIX = "/api/users/";
	private static final String PROFILE_PARSE_PATH = "/api/profile-analyses";
	private static final String PROFILE_PARSE_PREVIEW_PATH = "/api/profile-analyses/preview";
	private static final String NOTIFICATIONS_PATH = "/api/notifications";
	/** Acts only for the token's own user on meetings they take part in (see AssistantMeetingAccessService). */
	private static final String ASSISTANT_PATH_PREFIX = "/api/assistant/";

	private final FirebaseTokenVerifier firebaseTokenVerifier;
	private final AppUserRepository appUserRepository;

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		if (!firebaseTokenVerifier.enabled()) {
			write(response, HttpServletResponse.SC_UNAUTHORIZED, "Firebase authentication is not configured");
			return;
		}

		Optional<FirebaseIdentity> identity;
		try {
			identity = firebaseTokenVerifier.verify(header.substring(BEARER_PREFIX.length()).trim());
		} catch (FirebaseUnavailableException exception) {
			// A 401 would read as "signed out" or "not registered" to the frontend.
			write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Firebase is unavailable");
			return;
		}
		if (identity.isEmpty()) {
			write(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid Firebase token");
			return;
		}

		if (isPreAccountPath(request.getRequestURI())) {
			authenticate(identity.get(), List.of(new SimpleGrantedAuthority("ROLE_REGISTERING")));
			filterChain.doFilter(request, response);
			return;
		}

		Optional<AppUser> account = appUserRepository.findByFirebaseUid(identity.get().uid())
				.or(() -> findClaimableByEmail(identity.get()));
		if (account.isEmpty()) {
			if (isProfileParsePreview(request)) {
				authenticate(identity.get(), List.of(new SimpleGrantedAuthority("ROLE_REGISTERING")));
				filterChain.doFilter(request, response);
				return;
			}
			write(response, HttpServletResponse.SC_UNAUTHORIZED, "This account has not completed registration");
			return;
		}

		AppUser user = account.get();
		if (user.getStatus() != UserAccountStatus.APPROVED
				&& !STATUS_PATH.equals(request.getRequestURI())
				&& !LANGUAGE_PATH.equals(request.getRequestURI())
				&& !isDocumentAccess(request)
				&& !isProfileParsePreview(request)
				&& !isOwnMentorProfileUpdate(request, user)
				&& !isOwnUserProfileUpdate(request, user)) {
			write(response, HttpServletResponse.SC_FORBIDDEN, "This account is awaiting approval");
			return;
		}
		if (!isBrowserPath(request, user)) {
			write(response, HttpServletResponse.SC_FORBIDDEN, "This endpoint is only reachable through the app");
			return;
		}
		// A login deleted and created again in Firebase gets a new uid; the row was matched by email above.
		if (!identity.get().uid().equals(user.getFirebaseUid())) {
			user.setFirebaseUid(identity.get().uid());
			appUserRepository.save(user);
		}

		Set<UserRole> permissions = user.activePermissions();
		AppUserPrincipal principal = new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), permissions, user.getStatus(), user.getFirebaseUid());
		authenticate(principal, permissions.stream()
				.<GrantedAuthority>map(permission -> new SimpleGrantedAuthority("ROLE_" + permission.name()))
				.toList());
		filterChain.doFilter(request, response);
	}

	/**
	 * Linking a login to a row by email (and, below, re-binding the row to it) hands the row to whoever holds
	 * that address, so the address must be verified: Firebase lets anyone create an unverified login for an
	 * address that has none yet. Admins and coordinators are never claimed this way; their rows are seeded
	 * or created by an admin already linked to their uid.
	 */
	private Optional<AppUser> findClaimableByEmail(FirebaseIdentity identity) {
		// An OTG login reaches a row only through InstitutionLoginService, which checks which OTG person the row is
		// bound to; claiming by address here would skip that check.
		if (!identity.emailVerified() || identity.uid().startsWith(OtgLoginProvider.ID + "_")) {
			return Optional.empty();
		}
		return appUserRepository.findByEmailIgnoreCase(identity.email()).filter(FirebaseBearerFilter::claimableByEmail);
	}

	private static boolean claimableByEmail(AppUser user) {
		return !user.has(UserRole.ADMIN) && !user.has(UserRole.COORDINATOR);
	}

	/**
	 * A mentor is PENDING for the whole of onboarding, so the profile it produces
	 * could not otherwise be saved. Deliberately narrow: a PUT to this mentor's own
	 * profile path and nothing else. Unapproved mentors stay out of listings and
	 * matching regardless, because MentorProfileService filters to APPROVED.
	 */
	private static boolean isOwnMentorProfileUpdate(HttpServletRequest request, AppUser user) {
		if (!"PUT".equalsIgnoreCase(request.getMethod()) || !user.has(UserRole.MENTOR)) {
			return false;
		}
		String ownProfile = MENTOR_PROFILE_PATH_PREFIX + user.getId();
		String uri = request.getRequestURI();
		return ownProfile.equals(uri) || (ownProfile + AVAILABILITY_SETTINGS_SUFFIX).equals(uri);
	}

	/**
	 * The onboarding wizard reads and saves the person's own name, LinkedIn and
	 * contact preferences while PENDING; reading first keeps what the form does not ask.
	 */
	private static boolean isOwnUserProfileUpdate(HttpServletRequest request, AppUser user) {
		String method = request.getMethod();
		return ("PUT".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method))
				&& (USER_PROFILE_PATH_PREFIX + user.getId() + "/profile").equals(request.getRequestURI());
	}

	/**
	 * The paths the browser calls with its own token, directly or through a proxy route that forwards it.
	 * Everything else is reached through the Next.js proxy on the service credentials, with the caller's
	 * identity taken from its session; many of those endpoints trust ids sent in the request, so a token
	 * that reached them directly could act as anyone.
	 */
	private static boolean isBrowserPath(HttpServletRequest request, AppUser user) {
		String uri = request.getRequestURI();
		return STATUS_PATH.equals(uri)
				|| LANGUAGE_PATH.equals(uri)
				|| isDocumentAccess(request)
				|| isProfileParsePreview(request)
				|| ("POST".equalsIgnoreCase(request.getMethod()) && PROFILE_PARSE_PATH.equals(uri))
				|| NOTIFICATIONS_PATH.equals(uri)
				|| uri.startsWith(NOTIFICATIONS_PATH + "/")
				|| uri.startsWith(ASSISTANT_PATH_PREFIX)
				|| isOwnMentorProfileUpdate(request, user)
				|| isOwnUserProfileUpdate(request, user);
	}

	/**
	 * Founder onboarding runs after Firebase signup and before {@code app_user}
	 * exists, so these paths authenticate the token alone — same carve-out as
	 * {@code /api/auth/register}.
	 */
	private static boolean isPreAccountPath(String uri) {
		return uri.startsWith(REGISTER_PATH_PREFIX) || uri.startsWith(ONBOARDING_PATH_PREFIX);
	}

	/**
	 * A founder is PENDING for the whole of signup, so both uploading a document
	 * and listing what is already stored (to survive a resumed session) must work
	 * before approval.
	 */
	private static boolean isDocumentAccess(HttpServletRequest request) {
		return ("POST".equalsIgnoreCase(request.getMethod()) || "GET".equalsIgnoreCase(request.getMethod()))
				&& "/api/documents".equals(request.getRequestURI());
	}

	/**
	 * The signup CV step parses a file before the applicant has an account at
	 * all, and again while they are PENDING, so a token alone has to open it.
	 * Safe to widen this far because the endpoint persists nothing — no stored
	 * document, no profile_analysis row — so there is no organization to scope
	 * and nothing to leak between startups. It is not public, because it runs
	 * OCR and an LLM call and an open endpoint would be a cost and abuse route.
	 * The signup wizards upload the CV first and parse it by id, so the
	 * /documents/{id} form is included; it reads only the caller's own CV.
	 */
	private static boolean isProfileParsePreview(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return "POST".equalsIgnoreCase(request.getMethod())
				&& (PROFILE_PARSE_PREVIEW_PATH.equals(uri)
						|| uri.startsWith(PROFILE_PARSE_PREVIEW_PATH + "/documents/"));
	}

	private static void authenticate(Object principal, List<GrantedAuthority> authorities) {
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(principal, "firebase", authorities));
	}

	private static void write(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"message\":\"%s\"}".formatted(message));
	}
}

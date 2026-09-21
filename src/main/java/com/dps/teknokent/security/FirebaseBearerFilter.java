package com.dps.teknokent.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

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
	private static final String MENTOR_PROFILE_PATH_PREFIX = "/api/mentors/";
	private static final String AVAILABILITY_SETTINGS_SUFFIX = "/availability-settings";
	private static final String USER_PROFILE_PATH_PREFIX = "/api/users/";
	private static final String PROFILE_PARSE_PREVIEW_PATH = "/api/profile-analyses/preview";

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

		Optional<FirebaseIdentity> identity = firebaseTokenVerifier
				.verify(header.substring(BEARER_PREFIX.length()).trim());
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
				.or(() -> appUserRepository.findByEmailIgnoreCase(identity.get().email()));
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
				&& !isDocumentAccess(request)
				&& !isProfileParsePreview(request)
				&& !isOwnMentorProfileUpdate(request, user)
				&& !isOwnUserProfileUpdate(request, user)) {
			write(response, HttpServletResponse.SC_FORBIDDEN, "This account is awaiting approval");
			return;
		}
		if (user.getFirebaseUid() == null) {
			user.setFirebaseUid(identity.get().uid());
			appUserRepository.save(user);
		}

		AppUserPrincipal principal = new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), user.getFirebaseUid());
		authenticate(principal, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
		filterChain.doFilter(request, response);
	}

	/**
	 * A mentor is PENDING for the whole of onboarding, so the profile it produces
	 * could not otherwise be saved. Deliberately narrow: a PUT to this mentor's own
	 * profile path and nothing else. Unapproved mentors stay out of listings and
	 * matching regardless, because MentorProfileService filters to APPROVED.
	 */
	private static boolean isOwnMentorProfileUpdate(HttpServletRequest request, AppUser user) {
		if (!"PUT".equalsIgnoreCase(request.getMethod()) || user.getRole() != UserRole.MENTOR) {
			return false;
		}
		String ownProfile = MENTOR_PROFILE_PATH_PREFIX + user.getId();
		String uri = request.getRequestURI();
		return ownProfile.equals(uri) || (ownProfile + AVAILABILITY_SETTINGS_SUFFIX).equals(uri);
	}

	/** The onboarding wizard saves the person's own name, LinkedIn and contact preferences while PENDING. */
	private static boolean isOwnUserProfileUpdate(HttpServletRequest request, AppUser user) {
		return "PUT".equalsIgnoreCase(request.getMethod())
				&& (USER_PROFILE_PATH_PREFIX + user.getId() + "/profile").equals(request.getRequestURI());
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
	 */
	private static boolean isProfileParsePreview(HttpServletRequest request) {
		return "POST".equalsIgnoreCase(request.getMethod())
				&& PROFILE_PARSE_PREVIEW_PATH.equals(request.getRequestURI());
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

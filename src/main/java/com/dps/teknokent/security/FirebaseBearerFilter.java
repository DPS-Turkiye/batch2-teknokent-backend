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
	private static final String STATUS_PATH = "/api/auth/me";
	private static final String MENTOR_PROFILE_PATH_PREFIX = "/api/mentors/";

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

		if (request.getRequestURI().startsWith(REGISTER_PATH_PREFIX)) {
			authenticate(identity.get(), List.of(new SimpleGrantedAuthority("ROLE_REGISTERING")));
			filterChain.doFilter(request, response);
			return;
		}

		Optional<AppUser> account = appUserRepository.findByFirebaseUid(identity.get().uid())
				.or(() -> appUserRepository.findByEmailIgnoreCase(identity.get().email()));
		if (account.isEmpty()) {
			write(response, HttpServletResponse.SC_UNAUTHORIZED, "This account has not completed registration");
			return;
		}

		AppUser user = account.get();
		if (user.getStatus() != UserAccountStatus.APPROVED
				&& !STATUS_PATH.equals(request.getRequestURI())
				&& !isDocumentUpload(request)
				&& !isOwnMentorProfileUpdate(request, user)) {
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
		return "PUT".equalsIgnoreCase(request.getMethod())
				&& user.getRole() == UserRole.MENTOR
				&& (MENTOR_PROFILE_PATH_PREFIX + user.getId()).equals(request.getRequestURI());
	}

	private static boolean isDocumentUpload(HttpServletRequest request) {
		return "POST".equalsIgnoreCase(request.getMethod())
				&& "/api/documents".equals(request.getRequestURI());
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

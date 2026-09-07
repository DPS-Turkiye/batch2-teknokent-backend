#!/usr/bin/env node

/**
 * Mock Rate-Limited Backend Server
 * Simulates the Spring Boot backend with rate limiting
 * Usage: node mock-backend.js [port]
 */

const http = require('http');
const url = require('url');

const PORT = process.argv[2] || 8080;

// In-memory rate limiting storage
const rateLimitBuckets = new Map();

class TokenBucket {
	constructor(capacity, refillIntervalMs) {
		this.capacity = capacity;
		this.refillIntervalMs = refillIntervalMs;
		this.tokens = capacity;
		this.lastRefillTime = Date.now();
	}

	tryConsume(tokens) {
		this.refill();
		if (this.tokens >= tokens) {
			this.tokens -= tokens;
			return true;
		}
		return false;
	}

	getRemainingTokens() {
		this.refill();
		return this.tokens;
	}

	getSecondsToWait() {
		if (this.tokens >= 1) return 0;
		const timeSinceLastRefill = Date.now() - this.lastRefillTime;
		const timeToWait = this.refillIntervalMs - timeSinceLastRefill;
		return Math.max(1, Math.ceil(timeToWait / 1000));
	}

	refill() {
		const now = Date.now();
		const timeSinceLastRefill = now - this.lastRefillTime;
		if (timeSinceLastRefill >= this.refillIntervalMs) {
			this.tokens = this.capacity;
			this.lastRefillTime = now;
		}
	}
}

function getClientIdentifier(req) {
	const forwardedFor = req.headers['x-forwarded-for'];
	if (forwardedFor) {
		return forwardedFor.split(',')[0].trim();
	}
	const realIp = req.headers['x-real-ip'];
	if (realIp) {
		return realIp;
	}
	return req.socket.remoteAddress;
}

function resolveBucket(clientKey) {
	if (!rateLimitBuckets.has(clientKey)) {
		// 100 requests per minute per IP
		rateLimitBuckets.set(clientKey, new TokenBucket(100, 60000));
	}
	return rateLimitBuckets.get(clientKey);
}

const server = http.createServer((req, res) => {
	const pathname = url.parse(req.url).pathname;

	// CORS headers
	res.setHeader('Access-Control-Allow-Origin', '*');
	res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
	res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Forwarded-For, X-Real-IP');

	if (req.method === 'OPTIONS') {
		res.writeHead(200);
		res.end();
		return;
	}

	// Rate limiting for /api/assistant/* paths
	if (pathname.startsWith('/api/assistant/')) {
		const clientKey = getClientIdentifier(req);
		const bucket = resolveBucket(clientKey);

		if (!bucket.tryConsume(1)) {
			const secondsToWait = bucket.getSecondsToWait();
			res.writeHead(429, {
				'Content-Type': 'application/json',
				'X-Rate-Limit-Retry-After-Seconds': secondsToWait.toString(),
			});
			res.end(
				JSON.stringify({
					error: 'Too Many Requests',
					message: `Please retry after ${secondsToWait} seconds.`,
				})
			);
			return;
		}

		res.setHeader('X-Rate-Limit-Remaining', bucket.getRemainingTokens().toString());

		// Handle POST /api/assistant/bots
		if (pathname === '/api/assistant/bots' && req.method === 'POST') {
			let body = '';
			req.on('data', (chunk) => {
				body += chunk;
			});

			req.on('end', () => {
				try {
					const data = JSON.parse(body);
					if (!data.meetingUrl || typeof data.meetingUrl !== 'string') {
						res.writeHead(400, { 'Content-Type': 'application/json' });
						res.end(
							JSON.stringify({
								error: 'Bad Request',
								message: 'meetingUrl is required and must be a string',
							})
						);
						return;
					}

					res.writeHead(201, { 'Content-Type': 'application/json' });
					res.end(
						JSON.stringify({
							botId: 'bot-' + Math.random().toString(36).substr(2, 9),
							status: 'joining',
							name: 'Assistant Bot',
						})
					);
				} catch (err) {
					res.writeHead(400, { 'Content-Type': 'application/json' });
					res.end(
						JSON.stringify({
							error: 'Bad Request',
							message: 'Invalid JSON',
						})
					);
				}
			});
			return;
		}
	}

	// Health check
	if (pathname === '/health') {
		res.writeHead(200, { 'Content-Type': 'application/json' });
		res.end(
			JSON.stringify({
				status: 'UP',
				message: 'Mock backend is running',
			})
		);
		return;
	}

	// 404
	res.writeHead(404, { 'Content-Type': 'application/json' });
	res.end(
		JSON.stringify({
			error: 'Not Found',
		})
	);
});

server.listen(PORT, () => {
	console.log(`\n🚀 Mock Rate-Limited Backend Server`);
	console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
	console.log(`✅ Server listening on http://localhost:${PORT}`);
	console.log(`📊 Rate Limiting: 100 requests/minute per IP`);
	console.log(`🔗 Endpoints:`);
	console.log(`   POST /api/assistant/bots - Create bot (rate limited)`);
	console.log(`   GET  /health - Health check`);
	console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n`);
});

server.on('error', (err) => {
	console.error('❌ Server error:', err.message);
	process.exit(1);
});

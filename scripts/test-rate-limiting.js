#!/usr/bin/env node

/**
 * Rate Limiting Test Script
 * Sends ~20 requests per second to the rate-limited endpoint
 * and reports success/rate-limited responses
 */

const http = require('http');

const CONFIG = {
	HOST: process.argv[2] || 'localhost',
	PORT: process.argv[3] || 9090,
	PATH: '/api/assistant/bots',
	METHOD: 'POST',
	REQUESTS_PER_SECOND: 20,
	TOTAL_REQUESTS: 150,
	REQUEST_INTERVAL_MS: 1000 / 20, // 50ms between requests
};

const PAYLOAD = JSON.stringify({
	meetingUrl: 'https://meet.google.com/test-meeting-id',
});

let stats = {
	total: 0,
	success: 0,
	rateLimited: 0,
	errors: 0,
	responseTimes: [],
};

function makeRequest() {
	return new Promise((resolve) => {
		const startTime = Date.now();
		const options = {
			hostname: CONFIG.HOST,
			port: CONFIG.PORT,
			path: CONFIG.PATH,
			method: CONFIG.METHOD,
			headers: {
				'Content-Type': 'application/json',
				'Content-Length': Buffer.byteLength(PAYLOAD),
				// Use the same X-Forwarded-For IP for all requests to trigger rate limiting
				'X-Forwarded-For': '192.168.1.100',
			},
		};

		const req = http.request(options, (res) => {
			let data = '';
			res.on('data', (chunk) => {
				data += chunk;
			});

			res.on('end', () => {
				const duration = Date.now() - startTime;
				stats.total++;
				stats.responseTimes.push(duration);

				if (res.statusCode === 201) {
					stats.success++;
					console.log(`✓ [${stats.total}] 201 Created (${duration}ms)`);
				} else if (res.statusCode === 429) {
					stats.rateLimited++;
					const retryAfter = res.headers['x-rate-limit-retry-after-seconds'];
					const remaining = res.headers['x-rate-limit-remaining'];
					console.log(`⛔ [${stats.total}] 429 Too Many Requests | Retry after: ${retryAfter}s | Remaining: ${remaining}`);
				} else {
					stats.errors++;
					console.log(`✗ [${stats.total}] ${res.statusCode} ${res.statusMessage}`);
				}

				resolve();
			});
		});

		req.on('error', (err) => {
			stats.errors++;
			stats.total++;
			console.error(`✗ [${stats.total}] Error: ${err.message}`);
			resolve();
		});

		req.write(PAYLOAD);
		req.end();
	});
}

async function runTest() {
	console.log(`\n🚀 Rate Limiting Test Started`);
	console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
	console.log(`Target: http://${CONFIG.HOST}:${CONFIG.PORT}${CONFIG.PATH}`);
	console.log(`Total Requests: ${CONFIG.TOTAL_REQUESTS}`);
	console.log(`Request Rate: ${CONFIG.REQUESTS_PER_SECOND} req/s`);
	console.log(`Interval: ${CONFIG.REQUEST_INTERVAL_MS.toFixed(2)}ms between requests`);
	console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n`);

	const startTime = Date.now();

	// Send requests with interval
	for (let i = 0; i < CONFIG.TOTAL_REQUESTS; i++) {
		makeRequest();

		// Stagger requests
		if (i < CONFIG.TOTAL_REQUESTS - 1) {
			await new Promise((resolve) => setTimeout(resolve, CONFIG.REQUEST_INTERVAL_MS));
		}
	}

	// Wait for last requests to complete
	await new Promise((resolve) => setTimeout(resolve, 2000));

	const totalTime = Date.now() - startTime;
	const avgResponseTime = (stats.responseTimes.reduce((a, b) => a + b, 0) / stats.responseTimes.length).toFixed(2);

	console.log(`\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
	console.log(`📊 Test Results`);
	console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
	console.log(`Total Requests: ${stats.total}`);
	console.log(`✓ Success (201):        ${stats.success} (${((stats.success / stats.total) * 100).toFixed(1)}%)`);
	console.log(`⛔ Rate Limited (429):  ${stats.rateLimited} (${((stats.rateLimited / stats.total) * 100).toFixed(1)}%)`);
	console.log(`✗ Errors:              ${stats.errors}`);
	console.log(`\nResponse Times:`);
	console.log(`  Average: ${avgResponseTime}ms`);
	console.log(`  Min: ${Math.min(...stats.responseTimes)}ms`);
	console.log(`  Max: ${Math.max(...stats.responseTimes)}ms`);
	console.log(`\nTotal Test Duration: ${(totalTime / 1000).toFixed(2)}s`);
	console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n`);

	if (stats.rateLimited > 0) {
		console.log('✅ Rate limiting is WORKING! 429 responses detected.\n');
	} else {
		console.log('⚠️  No rate limiting detected. Check if backend is running.\n');
	}
}

runTest().catch(console.error);

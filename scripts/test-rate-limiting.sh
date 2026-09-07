#!/bin/bash

# Rate Limiting Test Script
# Sends rapid requests to test rate limiting
# Usage: ./test-rate-limiting.sh [requests] [delay_ms]

REQUESTS=${1:-150}
DELAY_MS=${2:-50}  # 50ms = 20 req/s

HOST="localhost"
PORT="8080"
ENDPOINT="/api/assistant/bots"
URL="http://${HOST}:${PORT}${ENDPOINT}"

PAYLOAD='{"meetingUrl":"https://meet.google.com/test-meeting"}'

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Stats
TOTAL=0
SUCCESS_201=0
RATE_LIMITED_429=0
ERRORS=0
DECLARE -a RESPONSE_CODES

echo ""
echo "🚀 Rate Limiting Test Started"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Target: ${URL}"
echo "Total Requests: ${REQUESTS}"
echo "Request Rate: $((1000 / DELAY_MS)) req/s"
echo "Delay: ${DELAY_MS}ms between requests"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

START_TIME=$(date +%s%N)

for ((i=1; i<=REQUESTS; i++)); do
    # Random IP for X-Forwarded-For
    RANDOM_IP="192.168.$((RANDOM % 256)).$((RANDOM % 256))"
    
    # Make request and capture response code
    RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X POST \
        -H "Content-Type: application/json" \
        -H "X-Forwarded-For: ${RANDOM_IP}" \
        -d "${PAYLOAD}" \
        "${URL}")
    
    HTTP_CODE=$(echo "${RESPONSE}" | tail -n 1)
    BODY=$(echo "${RESPONSE}" | head -n -1)
    
    TOTAL=$((TOTAL + 1))
    RESPONSE_CODES+=(${HTTP_CODE})
    
    if [ "${HTTP_CODE}" = "201" ]; then
        SUCCESS_201=$((SUCCESS_201 + 1))
        echo -e "${GREEN}✓${NC} [${i}] 201 Created"
    elif [ "${HTTP_CODE}" = "429" ]; then
        RATE_LIMITED_429=$((RATE_LIMITED_429 + 1))
        RETRY_AFTER=$(curl -s -w "" -I \
            -X POST \
            -H "Content-Type: application/json" \
            -H "X-Forwarded-For: ${RANDOM_IP}" \
            -d "${PAYLOAD}" \
            "${URL}" 2>/dev/null | grep -i "X-Rate-Limit-Retry-After" | cut -d' ' -f2)
        echo -e "${RED}⛔${NC} [${i}] 429 Too Many Requests (Retry after: ${RETRY_AFTER}s)"
    else
        ERRORS=$((ERRORS + 1))
        echo -e "${YELLOW}✗${NC} [${i}] ${HTTP_CODE} Error"
    fi
    
    # Delay before next request
    if [ $i -lt $REQUESTS ]; then
        sleep $(awk -v ms=$DELAY_MS 'BEGIN {printf "%.3f", ms/1000}')
    fi
done

END_TIME=$(date +%s%N)
TOTAL_TIME=$(( (END_TIME - START_TIME) / 1000000 ))

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 Test Results"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Total Requests: ${TOTAL}"
echo -e "${GREEN}✓ Success (201):${NC}       ${SUCCESS_201} ($((SUCCESS_201 * 100 / TOTAL))%)"
echo -e "${RED}⛔ Rate Limited (429):${NC}  ${RATE_LIMITED_429} ($((RATE_LIMITED_429 * 100 / TOTAL))%)"
echo -e "${YELLOW}✗ Errors:${NC}              ${ERRORS}"
echo ""
echo "Total Test Duration: ${TOTAL_TIME}ms"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

if [ ${RATE_LIMITED_429} -gt 0 ]; then
    echo -e "${GREEN}✅ Rate limiting is WORKING! 429 responses detected.${NC}"
    echo ""
else
    echo -e "${YELLOW}⚠️  No rate limiting detected. Check if backend is running.${NC}"
    echo ""
fi

#!/usr/bin/env python3
from __future__ import annotations

import argparse
import threading
from http.server import ThreadingHTTPServer

from matching_prototype.web_app import MatchingHandler
from matching_prototype.pool_web_app import PoolHandler
from web_app import BenchmarkHandler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run PDF Parse Lab, Mentor Match Lab, and Mentor Pool Lab together."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--parse-port", type=int, default=8765)
    parser.add_argument("--match-port", type=int, default=8766)
    parser.add_argument("--pool-port", type=int, default=8767)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if len({args.parse_port, args.match_port, args.pool_port}) != 3:
        raise SystemExit("Parse, match, and pool ports must be different.")
    parse_server = ThreadingHTTPServer((args.host, args.parse_port), BenchmarkHandler)
    match_server = ThreadingHTTPServer((args.host, args.match_port), MatchingHandler)
    pool_server = ThreadingHTTPServer((args.host, args.pool_port), PoolHandler)
    match_thread = threading.Thread(target=match_server.serve_forever, daemon=True)
    pool_thread = threading.Thread(target=pool_server.serve_forever, daemon=True)
    match_thread.start()
    pool_thread.start()
    print(f"PDF Parse Lab:   http://{args.host}:{args.parse_port}")
    print(f"Mentor Match Lab: http://{args.host}:{args.match_port}")
    print(f"Mentor Pool Lab:  http://{args.host}:{args.pool_port}")
    print("Press Ctrl+C to stop all labs.")
    try:
        parse_server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping all labs.")
    finally:
        parse_server.server_close()
        match_server.shutdown()
        match_server.server_close()
        pool_server.shutdown()
        pool_server.server_close()
        match_thread.join(timeout=2)
        pool_thread.join(timeout=2)


if __name__ == "__main__":
    main()

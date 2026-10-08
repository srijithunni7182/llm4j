import sys
import json

def log(msg):
    # Log to stderr so it doesn't interfere with stdout JSON-RPC
    sys.stderr.write(msg + "\n")
    sys.stderr.flush()

def main():
    log("Mock MCP Server Started")
    
    while True:
        try:
            line = sys.stdin.readline()
            if not line:
                break
            
            log(f"Received: {line.strip()}")
            request = json.loads(line)
            
            response = {
                "jsonrpc": "2.0",
                "id": request.get("id")
            }
            
            method = request.get("method")
            if method == "initialize":
                response["result"] = {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "serverInfo": {"name": "mock-server", "version": "1.0"}
                }
            elif method == "notifications/initialized":
                # No response needed for notification
                continue
            elif method == "tools/list":
                response["result"] = {
                    "tools": [{
                        "name": "echo",
                        "description": "Echoes back the input",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "message": {"type": "string"}
                            }
                        }
                    }]
                }
            elif method == "tools/call":
                params = request.get("params", {})
                if params.get("name") == "echo":
                     args = params.get("arguments", {})
                     response["result"] = {
                         "content": [{"type": "text", "text": f"Echo: {args.get('message')}"}]
                     }
                else:
                    response["error"] = {"code": -32601, "message": "Tool not found"}
            else:
                response["error"] = {"code": -32601, "message": "Method not found"}
            
            # Send response
            print(json.dumps(response))
            sys.stdout.flush()
            
        except Exception as e:
            log(f"Error: {e}")
            break

if __name__ == "__main__":
    main()

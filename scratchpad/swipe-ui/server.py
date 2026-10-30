from flask import Flask, send_from_directory
import os
d = os.path.dirname(os.path.abspath(__file__))
app = Flask(__name__)

@app.after_request
def nocache(r):
    r.headers["Cache-Control"] = "no-store"
    return r

@app.get("/")
def index():
    return send_from_directory(d, "index.html")

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8765)

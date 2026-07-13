"""
Minimal HTTP wrapper around Marker PDF-to-Markdown conversion.

Compatible with marker-pdf >= 1.10.x

Endpoints:
    GET  /health
    POST /convert

POST /convert
Content-Type: multipart/form-data

Field:
    file=<pdf>

Response:
{
    "markdown": "...",
    "pages": 15
}
"""

import os
import tempfile

from flask import Flask, jsonify, request

from marker.converters.pdf import PdfConverter
from marker.models import create_model_dict
from marker.output import text_from_rendered

app = Flask(__name__)

print("Loading Marker models...")

converter = PdfConverter(
    artifact_dict=create_model_dict()
)

print("Marker models loaded.")


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


@app.route("/convert", methods=["POST"])
def convert():

    if "file" not in request.files:
        return jsonify({"error": "No file provided"}), 400

    uploaded = request.files["file"]

    if uploaded.filename == "":
        return jsonify({"error": "Empty filename"}), 400

    if not uploaded.filename.lower().endswith(".pdf"):
        return jsonify({"error": "Only PDF files are supported"}), 400

    tmp_path = None

    try:

        with tempfile.NamedTemporaryFile(
            suffix=".pdf",
            delete=False
        ) as tmp:
            uploaded.save(tmp.name)
            tmp_path = tmp.name

        rendered = converter(tmp_path)

        markdown, metadata, images = text_from_rendered(rendered)

        pages = 0

        if metadata:
            pages = (
                metadata.get("pages")
                or metadata.get("page_count")
                or 0
            )

        return jsonify({
            "markdown": markdown,
            "pages": pages
        })

    except Exception as e:
        return jsonify({
            "error": str(e)
        }), 500

    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.remove(tmp_path)


if __name__ == "__main__":
    app.run(
        host="0.0.0.0",
        port=8100
    )
#!/usr/bin/env python3
"""QA ONLY — simulated ZEN 4 socket protocol. No real receiver or EPG data."""
import json
import re
import socket
import struct
import sys

# Real broadcasters + DVB service IDs (SID), as published in LyngSat 2026.
# SID is NOT interchangeable with the proprietary local ProgramId of an
# actual Zen 4, so the fixture borrows this value as ProgramId *ONLY in QA*.
# Actual receiver IDs ALWAYS come from its own catalogue, never these values.
# Badr 7: https://www.lyngsat.com/Badr-7-8-and-Eshail-2.html
# Es'hail 1: https://www.lyngsat.com/Eshail-1.html
# Satellite, transponder frequency and DVB SID are source-verified metadata.
RECORDS = [
    # Es'hail 1 25.5°E — Morocco SNRT, Al Jazeera
    {"sid":3, "name":"Al Aoula Inter", "tv":0, "sat":"Es'hail 1", "tp":"11547 V"},
    {"sid":13,"name":"Arryadia HD", "tv":0, "sat":"Es'hail 1", "tp":"11180 V PLS Stream 32"},
    # Badr 7 26°E — MBC multiplex (same channel HD and SD)
    {"sid":1, "name":"MBC HD", "tv":0, "sat":"Badr 7", "tp":"11270 V"},
    {"sid":10,"name":"MBC", "tv":0, "sat":"Badr 7", "tp":"11270 V"},
    {"sid":6, "name":"MBC 2", "tv":0, "sat":"Badr 7", "tp":"11270 V"},
    {"sid":7, "name":"MBC Action", "tv":0, "sat":"Badr 7", "tp":"11270 V"},
    # Es'hail 1 25.5°E — Al Jazeera 11604 H
    {"sid":10501,"name":"Aljazeera Channel", "tv":0, "sat":"Es'hail 1", "tp":"11604 H"},
    {"sid":10502,"name":"Aljazeera Mubasher", "tv":0, "sat":"Es'hail 1", "tp":"11604 H"},
    {"sid":10503,"name":"Aljazeera English", "tv":0, "sat":"Es'hail 1", "tp":"11604 H"},
    {"sid":10504,"name":"Aljazeera Documentary", "tv":0, "sat":"Es'hail 1", "tp":"11604 H"},
    # Additional verified Badr service IDs
    {"sid":9,"name":"MBC 3", "tv":0, "sat":"Badr 7", "tp":"11270 V"},
    {"sid":2,"name":"MBC 4", "tv":0, "sat":"Badr 7", "tp":"11270 V"},
    {"sid":8,"name":"MBC Max", "tv":0, "sat":"Badr 7", "tp":"11270 V"},
    # Real radio services — names/IDs from same published transponder lists
    {"sid":52,"name":"MBC FM", "tv":1, "sat":"Badr 7", "tp":"11270 V"},
    {"sid":54,"name":"Panorama FM", "tv":1, "sat":"Badr 7", "tp":"11270 V"},
    {"sid":11,"name":"Idaa al Watania", "tv":1, "sat":"Es'hail 1", "tp":"11180 V PLS Stream 48"},
    {"sid":80,"name":"Radio Meknes", "tv":1, "sat":"Es'hail 1", "tp":"11180 V PLS Stream 48"},
]
assert len({x["sid"] for x in RECORDS}) == len(RECORDS), "QA ProgramId collision"

def recv_exact(sock, n):
    out = bytearray()
    while len(out) < n:
        buf = sock.recv(n-len(out))
        if not buf:
            raise EOFError("ZEN QA client closed connection")
        out.extend(buf)
    return bytes(out)

def login_block():
    plaintext=bytearray(108)
    plaintext[:12]=b"39WwijOog54a"
    plaintext[12:20]=b"ZENQA123"
    plaintext[20:28]=b"ZEN 4 QA"
    plaintext[52:60]=b"QARECV01"
    plaintext[84]=0x40   # JSON transport
    return bytes(plaintext[107-i] ^ 0x5B for i in range(108))

def respond(sock, kind, content):
    b=json.dumps(content,ensure_ascii=False,separators=(",",":")).encode("utf-8")
    sock.sendall(struct.pack("<4siii",b"GCDH",len(b),kind,0)+b)

def serve(sock):
    print("QA fixture connected",flush=True)
    logged=False
    while True:
        h=recv_exact(sock,15)
        if not(h.startswith(b"Start") and h.endswith(b"End")):
            print("unexpected wire header",h,flush=True);break
        size=int(h[5:12])
        req=recv_exact(sock,size).decode("utf-8")
        if not logged:
            if 'request="998"' not in req:
                print("unexpected first request",repr(req[:130]),flush=True);break
            sock.sendall(login_block());logged=True
            continue
        match=re.search(r'"request"\s*:\s*"(\d+)"',req)
        kind=int(match.group(1)) if match else -1
        print("request",kind,flush=True)
        if kind in (14,15):
            respond(sock,kind,{"array":[{"ChannelNum":str(len(RECORDS))}]})
        elif kind==19:
            respond(sock,kind,{"array":[{"TvProgramCount":str(len(RECORDS))}]})
        elif kind==0:
            # A QA-only mismatch makes it impossible for launcher to select ServiceID
            # when ProgramId is required for actual Zen request 1000. Synthetic
            # ServiceID is NOT any real satellite transmission identifier.
            result=[{"ServiceID":str(row["sid"]+1000000),"ProgramId":str(row["sid"]),
                     "ServiceName":row["name"],"TvState":str(row["tv"])} for row in RECORDS]
            respond(sock,0,{"array":result})
        elif kind==1000:
            payload=json.loads(req)
            ch=payload.get("array",[{}])[0]
            print("tune target",ch.get("ProgramId"),"state",ch.get("TvState"),flush=True)
            respond(sock,kind,{"array":[]})
        elif kind in (3,1040):
            respond(sock,kind,{"array":[]})
        else:
            respond(sock,kind,{"array":[]})

def main():
    # Host service exposed to Android Emulator via 10.0.2.2.
    with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        server.bind(("0.0.0.0",20000));server.listen(4)
        print("QA receiver ready on 20000 — REAL satellite names/SIDs, synthetic Zen ProgramIds; not physical hardware",flush=True)
        print("SATELLITE CATALOGUE "+json.dumps(RECORDS,ensure_ascii=False),flush=True)
        while True:
            client,addr=server.accept()
            with client:
                client.settimeout(55)
                try:serve(client)
                except (EOFError,ConnectionError,TimeoutError,OSError) as error:
                    print("receiver session closed",repr(error),flush=True)

if __name__=="__main__":main()

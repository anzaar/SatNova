#!/usr/bin/env python3
"""QA ONLY — simulated ZEN 4 socket protocol. No real receiver or EPG data."""
import json
import re
import socket
import struct
import sys

RECORDS = [
    ("101","قناة تجريبية أولى",0),
    ("102","قناة تجريبية أولى HD",0),
    ("103","قناة تجريبية رياضية",0),
    ("104","قناة تجريبية وثائقية",0),
    ("105","قناة تجريبية للأطفال",0),
    ("106","قناة تجريبية إخبارية",0),
    ("107","إذاعة تجريبية أولى",1),
    ("108","إذاعة تجريبية ثانية",1),
]
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
            result=[{"ProgramId":k,"ServiceName":n,"TvState":str(t)} for k,n,t in RECORDS]
            respond(sock,0,{"array":result})
        elif kind in (3,1040,1000):
            respond(sock,kind,{"array":[]})
        else:
            respond(sock,kind,{"array":[]})

def main():
    # Host service exposed to Android Emulator via 10.0.2.2.
    with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        server.bind(("0.0.0.0",20000));server.listen(4)
        print("QA fixture server ready on 20000 — synthetic names, not real TV data",flush=True)
        while True:
            client,addr=server.accept()
            with client:
                client.settimeout(55)
                try:serve(client)
                except (EOFError,ConnectionError,TimeoutError,OSError) as error:
                    print("receiver session closed",repr(error),flush=True)

if __name__=="__main__":main()

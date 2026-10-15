#!/usr/bin/env python3
"""Generates eval.tsv: realistic prompts with planted sensitive values and decoys, labelled by span.

Each line is:  text <TAB> TYPE=value||TYPE=value   (empty label column = nothing sensitive)
Run:  python3 generate.py > eval.tsv     (seeded, so the output is reproducible)
"""
import random
import string

rnd = random.Random(20261015)

# ---- check-digit algorithms (mirrors dev.veil.detect.Checksums) ----
D = [[0,1,2,3,4,5,6,7,8,9],[1,2,3,4,0,6,7,8,9,5],[2,3,4,0,1,7,8,9,5,6],[3,4,0,1,2,8,9,5,6,7],[4,0,1,2,3,9,5,6,7,8],
     [5,9,8,7,6,0,4,3,2,1],[6,5,9,8,7,1,0,4,3,2],[7,6,5,9,8,2,1,0,4,3],[8,7,6,5,9,3,2,1,0,4],[9,8,7,6,5,4,3,2,1,0]]
P = [[0,1,2,3,4,5,6,7,8,9],[1,5,7,6,2,8,3,0,9,4],[5,8,0,3,7,9,6,1,4,2],[8,9,1,6,0,4,3,5,2,7],[9,4,5,3,1,2,6,8,7,0],
     [4,2,8,6,5,7,3,9,0,1],[2,7,9,3,8,0,6,4,1,5],[7,0,4,6,9,1,3,2,5,8]]
INV = [0,4,3,2,1,5,6,7,8,9]

def verhoeff_valid(num):
    c = 0
    for i, ch in enumerate(reversed(num)):
        c = D[c][P[i % 8][int(ch)]]
    return c == 0

def verhoeff_digit(num):
    c = 0
    for i, ch in enumerate(reversed(num)):
        c = D[c][P[(i + 1) % 8][int(ch)]]
    return str(INV[c])

def luhn_valid(num):
    s, dbl = 0, False
    for ch in reversed(num):
        d = int(ch)
        if dbl:
            d *= 2
            if d > 9: d -= 9
        s += d
        dbl = not dbl
    return s % 10 == 0

def luhn_digit(num):
    for d in "0123456789":
        if luhn_valid(num + d): return d

def digits(n):
    return "".join(rnd.choice(string.digits) for _ in range(n))

def aadhaar():
    base = rnd.choice("23456789") + digits(10)
    n = base + verhoeff_digit(base)
    style = rnd.random()
    return f"{n[:4]} {n[4:8]} {n[8:]}" if style < 0.6 else (f"{n[:4]}-{n[4:8]}-{n[8:]}" if style < 0.75 else n)

def card():
    prefix, length = rnd.choice([("4", 16), ("51", 16), ("55", 16), ("2221", 16), ("37", 15), ("6521", 16), ("6011", 16)])
    body = prefix + digits(length - len(prefix) - 1)
    n = body + luhn_digit(body)
    if length == 16 and rnd.random() < 0.6:
        sep = rnd.choice([" ", "-"])
        return sep.join(n[i:i + 4] for i in range(0, 16, 4))
    return n

def pan():
    L = string.ascii_uppercase
    return "".join(rnd.choice(L) for _ in range(3)) + rnd.choice("PC") + rnd.choice(L) + digits(4) + rnd.choice(L)

GST = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
def gstin():
    first = f"{rnd.randint(1, 37):02d}" + pan() + rnd.choice("123") + "Z"
    s = 0
    for i, ch in enumerate(first):
        p = GST.index(ch) * (1 if i % 2 == 0 else 2)
        s += p // 36 + p % 36
    return first + GST[(36 - s % 36) % 36]

def iban():
    country, bban = rnd.choice([("GB", "WEST" + digits(14)), ("DE", digits(18)), ("FR", digits(10) + "A" + digits(12))])
    rearranged = bban + country + "00"
    num = "".join(str(int(c, 36)) for c in rearranged)
    check = 98 - int(num) % 97
    raw = f"{country}{check:02d}{bban}"
    return " ".join(raw[i:i + 4] for i in range(0, len(raw), 4)) if rnd.random() < 0.5 else raw

def ssn():
    area = rnd.choice([a for a in range(1, 900) if a != 666])
    return f"{area:03d}-{rnd.randint(1, 99):02d}-{rnd.randint(1, 9999):04d}"

def phone_in():
    n = rnd.choice("6789") + digits(9)
    style = rnd.random()
    if style < 0.4: return "+91 " + n[:5] + " " + n[5:]
    if style < 0.7: return "+91-" + n
    return n

def phone_us():
    return f"({rnd.randint(201, 989)}) {rnd.randint(200, 999)}-{digits(4)}"

def ifsc():
    return rnd.choice(["HDFC", "ICIC", "SBIN", "UTIB", "KKBK"]) + "0" + digits(6)

def ip():
    return rnd.choice(["10", "172", "192"]) + f".{rnd.randint(0, 255)}.{rnd.randint(0, 255)}.{rnd.randint(1, 254)}"

def aws():
    return "AKIA" + "".join(rnd.choice(string.ascii_uppercase + "234567") for _ in range(16))

def github():
    return "ghp_" + "".join(rnd.choice(string.ascii_letters + string.digits) for _ in range(36))

def openai():
    return "sk-proj-" + "".join(rnd.choice(string.ascii_letters + string.digits) for _ in range(40))

def jwt():
    b = lambda n: "".join(rnd.choice(string.ascii_letters + string.digits + "_-") for _ in range(n))
    return "eyJhbGciOiJIUzI1NiJ9.eyJ" + b(30) + "." + b(43)

def password():
    return rnd.choice(["Summer@2026", "Tr0ub4dor&3", "Welcome#123", "Pa55w0rd!x", "Kavya@1993", "q9X!mR2#vL"])

def email(name):
    first, last = name.lower().split()[:2]
    return f"{first}.{last}@{rnd.choice(['gmail.com', 'acme-corp.in', 'outlook.com', 'yahoo.co.in', 'example.org'])}"

def bank_account():
    return digits(rnd.choice([11, 12, 14]))

FIRST = ["Priya", "Rahul", "Ananya", "Vikram", "Sneha", "Arjun", "Kavita", "Rohan", "Meera", "Sanjay", "Deepika",
         "Karan", "Neha", "Aditya", "Pooja", "Michael", "Jennifer", "David", "Sarah", "Robert", "Emily", "Daniel"]
LAST = ["Sharma", "Verma", "Iyer", "Reddy", "Nair", "Gupta", "Patel", "Mehta", "Kulkarni", "Banerjee", "Singh",
        "Johnson", "Williams", "Brown", "Miller", "Anderson", "Thomas"]

def person():
    return f"{rnd.choice(FIRST)} {rnd.choice(LAST)}"

# ---- decoys: look sensitive, aren't ----
def rand12_invalid():
    while True:
        n = rnd.choice("23456789") + digits(11)
        if not verhoeff_valid(n): return f"{n[:4]} {n[4:8]} {n[8:]}" if rnd.random() < 0.5 else n

def rand16_invalid():
    while True:
        n = "4" + digits(15)
        if not luhn_valid(n): return n

def uuid():
    h = lambda n: "".join(rnd.choice("0123456789abcdef") for _ in range(n))
    return f"{h(8)}-{h(4)}-{h(4)}-{h(4)}-{h(12)}"

def sha():
    return "".join(rnd.choice("0123456789abcdef") for _ in range(40))

POSITIVE = [
    ("Customer {PERSON} called about a failed refund. Aadhaar {AADHAAR}, registered mobile {PHONE_IN}.", ["PERSON", "AADHAAR", "PHONE"]),
    ("Draft a reply to {PERSON} ({EMAIL}) explaining why their card {CARD} was declined.", ["PERSON", "EMAIL", "CREDIT_CARD"]),
    ("KYC pending for PAN {PAN}. Please verify the Aadhaar number {AADHAAR} before onboarding.", ["PAN", "AADHAAR"]),
    ("Vendor GSTIN is {GSTIN}, bank IFSC {IFSC}, account number {BANKACC}. Prepare the payment advice.", ["GSTIN", "IFSC", "BANK_ACCOUNT"]),
    ("Summarise this ticket: user {EMAIL} says the app crashes. Contact them on {PHONE_IN}.", ["EMAIL", "PHONE"]),
    ("Our prod config has aws_access_key_id = {AWS} and it stopped working, why?", ["API_KEY"]),
    ("Fix this script, it fails with 401: export GITHUB_TOKEN={GITHUB}", ["API_KEY"]),
    ("client = OpenAI(api_key=\"{OPENAI}\") gives an error, what am I doing wrong", ["API_KEY"]),
    ("Decode this token for me: {JWT}", ["JWT"]),
    ("The db password is {PASSWORD}, can you write a connection snippet for Spring Boot?", ["PASSWORD"]),
    ("Please wire the refund to IBAN {IBAN} for {PERSON}.", ["IBAN", "PERSON"]),
    ("Employee SSN {SSN} was entered twice in payroll, write an apology email to Mr. {LASTONLY}.", ["US_SSN", "PERSON"]),
    ("Call the US client at {PHONE_US} tomorrow and confirm the demo.", ["PHONE"]),
    ("The server at {IP} is refusing SSH connections after the update, help me debug.", ["IP_ADDRESS"]),
    ("Dear {PERSON}, your loan application was approved. Card ending details: {CARD}.", ["PERSON", "CREDIT_CARD"]),
    ("Patient {PERSON}, Aadhaar {AADHAAR}, was admitted on 3 March. Write the insurance claim summary.", ["PERSON", "AADHAAR"]),
    ("Translate to Hindi: Hello {PERSON}, your OTP was sent to {PHONE_IN}.", ["PERSON", "PHONE"]),
    ("Compare these two applicants: {PERSON} (PAN {PAN}) and {PERSON2} (PAN {PAN2}).", ["PERSON", "PAN", "PERSON", "PAN"]),
    ("Write SQL to find orders placed by {EMAIL} using card {CARD}.", ["EMAIL", "CREDIT_CARD"]),
    ("Spoke to {PERSON} today; her alternate number is {PHONE_US} and email {EMAIL}.", ["PERSON", "PHONE", "EMAIL"]),
    ("Ms. {LASTONLY} reported that her account number {BANKACC} shows a duplicate debit.", ["PERSON", "BANK_ACCOUNT"]),
    ("Use this key in the curl example: -H 'Authorization: Bearer {OPENAI}'", ["API_KEY"]),
    ("Format this address label for {PERSON}, phone {PHONE_IN}, GSTIN {GSTIN}.", ["PERSON", "PHONE", "GSTIN"]),
    ("My Aadhaar is {AADHAAR} and PAN is {PAN}, which ITR form should I file?", ["AADHAAR", "PAN"]),
    ("Charge the corporate card {CARD} and send the invoice to {EMAIL}.", ["CREDIT_CARD", "EMAIL"]),
]

NEGATIVE = [
    "Order {RAND12} was delivered late, draft a polite apology to the customer.",
    "Tracking number {RAND16} shows the parcel is stuck in transit, what should I say?",
    "Our request id {UUID} keeps timing out at the gateway, how do I add retries?",
    "Revert commit {SHA} and explain what a git revert does.",
    "Upgrade the library from v2.3.1.4 to v2.4.0 and list breaking changes.",
    "Invoice INV-2026-000{N3} totals Rs 12,49,999. Write a reminder email to the finance team.",
    "Explain how the Luhn algorithm validates card numbers in general.",
    "What does an Aadhaar number look like and how is it verified?",
    "The meeting is on 14/10/2026 at 15:30 in room 4B, send a calendar invite.",
    "Calculate 1234567890 times 42 and explain the steps.",
    "Write a haiku about the monsoon in Mumbai.",
    "Our API returns HTTP 503 at 12:45:10, summarise likely causes.",
    "Set the timeout to 30000 ms and the pool size to 50 in application.yml.",
    "What is the difference between PAN and TAN in Indian taxation?",
    "Batch {RAND12} failed quality checks, write the deviation report.",
    "The password field should require at least 12 characters, write the validation.",
    "Explain the policy: employees must not share an api_key in chat.",
    "Customer support asked: why is my refund taking 7 days?",
    "Please summarise the attached release notes for version 10.4.2.",
    "Population of India is about 1450000000, write a short note on demographics.",
]

def fill(template):
    labels = []
    values = {}
    def val(key):
        if key in values: return values[key]
        gen = {
            "PERSON": person, "PERSON2": person, "AADHAAR": aadhaar, "PAN": pan, "PAN2": pan, "CARD": card,
            "GSTIN": gstin, "IFSC": ifsc, "BANKACC": bank_account, "PHONE_IN": phone_in, "PHONE_US": phone_us,
            "AWS": aws, "GITHUB": github, "OPENAI": openai, "JWT": jwt, "PASSWORD": password, "IBAN": iban,
            "SSN": ssn, "IP": ip, "LASTONLY": lambda: rnd.choice(LAST), "RAND12": rand12_invalid,
            "RAND16": rand16_invalid, "UUID": uuid, "SHA": sha, "N3": lambda: digits(3),
        }.get(key)
        if key == "EMAIL":
            v = email(values.get("PERSON") or person())
        else:
            v = gen()
        values[key] = v
        return v
    text = template
    while "{" in text:
        start = text.index("{")
        end = text.index("}", start)
        key = text[start + 1:end]
        text = text[:start] + val(key) + text[end + 1:]
    return text, values

TYPE_OF = {"PERSON": "PERSON", "PERSON2": "PERSON", "AADHAAR": "AADHAAR", "PAN": "PAN", "PAN2": "PAN",
           "CARD": "CREDIT_CARD", "GSTIN": "GSTIN", "IFSC": "IFSC", "BANKACC": "BANK_ACCOUNT", "PHONE_IN": "PHONE",
           "PHONE_US": "PHONE", "AWS": "API_KEY", "GITHUB": "API_KEY", "OPENAI": "API_KEY", "JWT": "JWT",
           "PASSWORD": "PASSWORD", "IBAN": "IBAN", "SSN": "US_SSN", "IP": "IP_ADDRESS", "LASTONLY": "PERSON",
           "EMAIL": "EMAIL"}

def main():
    lines = []
    for _ in range(14):
        for template, _types in POSITIVE:
            text, values = fill(template)
            labels = [f"{TYPE_OF[k]}={v}" for k, v in values.items() if k in TYPE_OF]
            lines.append(text + "\t" + "||".join(labels))
    for _ in range(8):
        for template in NEGATIVE:
            text, _ = fill(template)
            lines.append(text + "\t")
    rnd.shuffle(lines)
    print("\n".join(lines))

if __name__ == "__main__":
    main()

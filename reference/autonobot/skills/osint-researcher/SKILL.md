# OSINT Researcher Skill

Advanced open-source intelligence gathering, reconnaissance, profiling, and threat intelligence collection.

---

## Capabilities

### 1. Domain & IP Reconnaissance
Gather intelligence on domains and IP addresses:
- Whois lookups
- DNS record enumeration
- IP geolocation
- ASN information
- Subdomain discovery
- DNS history

**Example:**
```
"Get all info on example.com - Whois, DNS records, subdomains"
```

### 2. Email Enumeration & Verification
Find and verify email addresses:
- Email discovery from domain
- Email verification (valid/invalid)
- Breach database checking
- Email pattern analysis
- Associated domains per email

**Example:**
```
"Find all emails associated with @company.com"
"Check if john@example.com was in any data breaches"
```

### 3. Social Media Profiling
Gather intelligence from social platforms:
- Username search across platforms
- Profile information extraction
- Account history & changes
- Connected accounts
- Public posts/activity analysis

**Example:**
```
"Find all social media accounts for username 'johndoe'"
"Profile @username on Twitter, Instagram, LinkedIn"
```

### 4. Public Records Search
Access publicly available records:
- Business registrations
- Property records
- Court records (public)
- Regulatory filings
- News archives

**Example:**
```
"Search public records for John Smith in California"
"Find business registrations for TechCorp Inc"
```

### 5. Network Mapping & Infrastructure
Map network infrastructure:
- Active hosts detection
- Service enumeration
- Port scanning (passive)
- Certificate analysis
- Technology stack identification

**Example:**
```
"Map infrastructure for example.com"
"Identify what tech stack is used on this website"
```

### 6. Threat Intelligence & CVEs
Monitor security threats:
- CVE (Common Vulnerabilities & Exposures) lookup
- Breach database search
- Malware/IOC tracking
- Security advisories
- Exploit database search

**Example:**
```
"Search CVEs for Apache 2.4.41"
"Check if domain was involved in security breaches"
```

### 7. Data Breach Checking
Verify if data was compromised:
- Check against known breaches (HaveIBeenPwned, etc.)
- Leaked credential search
- Dark web monitoring alerts
- Exposure assessment
- Compromise timeline

**Example:**
```
"Check if john@example.com appears in data breaches"
"Was this company mentioned in any security incidents?"
```

### 8. Username & Identity Search
Find accounts across internet:
- Username availability checker
- Username history
- Cross-platform presence
- Identity verification
- Fake account detection

**Example:**
```
"Search for 'johndoe' username across all platforms"
"Check if this username is available on: Twitter, GitHub, Discord"
```

### 9. Reverse Image Search & Analysis
Analyze images for OSINT:
- Reverse image search
- Metadata extraction (EXIF)
- Image source/origin tracking
- Similar image detection
- Geolocation from images

**Example:**
```
"Find where this image came from"
"Extract metadata from this photo"
"Search for similar images online"
```

### 10. Document & Metadata Analysis
Analyze documents for intelligence:
- PDF metadata extraction
- Document properties
- Author information
- Version history
- Hidden metadata recovery

**Example:**
```
"Extract metadata from this PDF"
"Find author information and version history"
```

### 11. API & Service Enumeration
Discover exposed APIs and services:
- Public API discovery
- S3 bucket enumeration
- Git repository search (public)
- Configuration file discovery
- Credential exposure detection

**Example:**
```
"Find all public APIs for this company"
"Search for exposed S3 buckets or Git repos"
```

### 12. Geolocation & Mapping
Determine physical locations:
- IP geolocation
- Image geolocation (reverse)
- Business location finder
- Phone number location
- Address verification

**Example:**
```
"Geolocate this IP address"
"Find the location of this business address"
```

---

## Tools Available

| Tool | Purpose |
|------|---------|
| `whois_lookup` | Domain/IP Whois information |
| `dns_enumeration` | DNS records and subdomain discovery |
| `email_finder` | Find emails associated with domain |
| `email_verification` | Verify if email is valid |
| `breach_check` | Check data breach databases |
| `social_media_search` | Search profiles across platforms |
| `username_search` | Find accounts with specific username |
| `public_records_search` | Search business/property records |
| `network_mapper` | Map network infrastructure |
| `port_scanner` | Passive port/service detection |
| `cve_lookup` | Search CVE database |
| `threat_intel` | Threat intelligence gathering |
| `reverse_image_search` | Find image source |
| `metadata_extractor` | Extract file/image metadata |
| `api_discovery` | Discover exposed APIs |
| `geolocation` | Geolocate IPs and images |

---

## Workflow Examples

### Example 1: Full Company Reconnaissance

```
1. "Get all info on techcorp.com"
   → Whois, DNS, IP location, hosting provider

2. "Find subdomains for techcorp.com"
   → admin.techcorp.com, api.techcorp.com, etc.

3. "Find all emails for techcorp.com"
   → john@techcorp.com, admin@techcorp.com, etc.

4. "Search for key employees on LinkedIn/Twitter"
   → Find company executives, researchers

5. "Check for any security breaches"
   → Search if techcorp.com was compromised

Result: Complete company profile
```

### Example 2: Verify Email/Person

```
1. "Check if john@example.com is real"
   → Email verification

2. "Check if in any data breaches"
   → Breach database search

3. "Find social media profiles for John"
   → Twitter, LinkedIn, GitHub, etc.

4. "Get public records for John Smith"
   → Business, property, court records

Result: Complete person profile
```

### Example 3: Threat Assessment

```
1. "Search CVEs for Apache 2.4.41"
   → Find vulnerabilities

2. "Check if this website uses Apache 2.4.41"
   → Technology stack identification

3. "Search threat intelligence for this domain"
   → Known threats/incidents

4. "Check for exposed credentials"
   → Credential search

Result: Security threat assessment
```

---

## Data Sources

**Publicly Available:**
- Whois databases
- DNS records
- Business registrations
- Property records
- News archives
- Public social media profiles
- CVE/NVD databases
- Breach notification databases

**Third-party APIs:**
- IPQualityScore
- AbuseIPDB
- HaveIBeenPwned
- VirusTotal
- Shodan

---

## Legal & Ethical Guidelines

**This skill is for:**
✅ Security research (authorized)
✅ Threat intelligence (professional)
✅ Due diligence (legal)
✅ Personal brand monitoring
✅ Public information gathering

**This skill is NOT for:**
❌ Unauthorized hacking
❌ Privacy violations
❌ Stalking/harassment
❌ Illegal surveillance
❌ Unauthorized access

**All data gathered is from public sources only.**

---

## Output Formats

**Results include:**
- Structured data (JSON)
- Timeline of findings
- Risk assessment
- Source attribution
- Confidence levels
- Data freshness timestamps

---

## Integration with Other Skills

**Works with:**
- **Security Engineer** — Find vulnerabilities
- **Data Scientist** — Analyze patterns
- **AI Hunter Pro** — Advanced threat hunting
- **Analytics** — Correlate findings

---

## Examples

### Simple
```
"Whois lookup for example.com"
→ Owner, registrar, registration date

"Check if email@example.com in breaches"
→ Breach count, affected databases
```

### Intermediate
```
"Full recon on company.com - DNS, IPs, emails, employees"
→ Complete company intelligence

"Find all accounts for username 'johndoe'"
→ Twitter, GitHub, Discord, Reddit, etc.
```

### Advanced
```
"Comprehensive threat assessment: domain check + CVE search + breach history + employee lookup"
→ Full security posture report

"Reverse image search + EXIF extraction + geolocation + source tracking"
→ Complete image intelligence
```

---

## Next Steps

1. **Specify target** (domain, email, person, IP)
2. **Choose reconnaissance depth** (quick, standard, comprehensive)
3. **Get results** with risk assessment & sources
4. **Use findings** for security/research

Your OSINT reconnaissance toolkit is ready.

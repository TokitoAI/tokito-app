# Security Policy

## Reporting

Do not file a public issue for a vulnerability involving camera/audio privacy,
model artifact integrity, unsafe tool guidance, authentication, or arbitrary
native code execution. Use GitHub private vulnerability reporting for this
repository or contact the Tokito maintainers through the organization security
channel.

Include the affected revision, device/runtime profile, reproduction conditions,
and impact. Do not attach private board images, recordings, credentials, or
proprietary designs to a public report.

## Supported versions

There is no released runtime yet. Once releases begin, the latest minor release
line receives security fixes. Pre-release builds are unsupported outside their
documented evaluation window.

## Security boundary

Raw camera/audio data remains on-device by default. Model artifacts are
hash-addressed and verified before activation. Tool outputs are untrusted
observations, not authority to modify a design or energize hardware. See
[docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md).

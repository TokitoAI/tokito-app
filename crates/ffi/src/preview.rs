use crate::{runtime, CompanionError};
use tokito_companion_core::{preview::cache::PreviewCache, CloudError};

#[uniffi::export]
pub fn get_schematic_preview(
    base: String,
    token: String,
    project: String,
    design: String,
    version: u64,
    sheet: String,
    state_path: String,
) -> Result<String, CompanionError> {
    let identity = crate::identity::identity_store(&state_path)?;
    let result = runtime().block_on(tokito_companion_core::preview::get_preview(
        &base, &token, &project, &design, version, &sheet,
    ));
    if matches!(result, Err(CloudError::Unauthorized)) {
        identity.revoke(&base, &token)?;
    }
    let preview = result.map_err(CompanionError::from)?;
    if let Some(account) = identity.cached_account(&base, &token)? {
        PreviewCache::open(
            &std::path::Path::new(&state_path).with_file_name("schematic-previews-v1.sqlite3"),
            &base,
            &account.email,
        )?
        .put(&preview)?;
    }
    encode(preview, false)
}

#[uniffi::export]
pub fn cached_schematic_preview(
    base: String,
    token: String,
    project: String,
    design: String,
    version: u64,
    sheet: String,
    state_path: String,
) -> Result<Option<String>, CompanionError> {
    let identity = crate::identity::identity_store(&state_path)?;
    let Some(account) = identity.cached_account(&base, &token)? else {
        return Ok(None);
    };
    let cache = PreviewCache::open(
        &std::path::Path::new(&state_path).with_file_name("schematic-previews-v1.sqlite3"),
        &base,
        &account.email,
    )?;
    cache
        .get(&project, &design, &sheet, version)?
        .map(|preview| encode(preview, true))
        .transpose()
}
fn encode(
    preview: tokito_companion_core::preview::SchematicPreview,
    cached: bool,
) -> Result<String, CompanionError> {
    let mut value = serde_json::to_value(preview).map_err(|_| CompanionError::Unexpected)?;
    value["cached"] = serde_json::json!(cached);
    serde_json::to_string(&value).map_err(|_| CompanionError::Unexpected)
}

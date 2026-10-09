use crate::{runtime, CompanionError};

#[uniffi::export]
pub fn get_schematic_preview(
    base: String,
    token: String,
    project: String,
    design: String,
    version: u64,
    sheet: String,
) -> Result<String, CompanionError> {
    let preview = runtime()
        .block_on(tokito_companion_core::preview::get_preview(
            &base, &token, &project, &design, version, &sheet,
        ))
        .map_err(CompanionError::from)?;
    serde_json::to_string(&preview).map_err(|_| CompanionError::Unexpected)
}

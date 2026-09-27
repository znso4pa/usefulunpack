//! Cxdec bootstrap runtime key derivation.

use crate::crypto::bootstrap_alg::{
    BootstrapTableKeys, KEY_BLOCK_LEN, control_block_from_drip_bytes, derive_key_block,
    derive_table_keys, derive_unique_block, derive_verified_material, encode_utf16le, xor_in_place,
};
use crate::crypto::exe_resource::BootstrapParams;
use crate::io::BinaryReader;

#[derive(Debug, Clone)]
pub struct BootstrapStaticData {
    pub params_payload: Vec<u8>,
    pub params: BootstrapParams,
    pub warning: String,
    pub unique_utf16le: Vec<u8>,
    pub archive_unique_key: [u8; 8],
}

#[derive(Debug, Clone)]
pub struct BootstrapDerivedKeys {
    pub params: BootstrapParams,
    pub control_block: Vec<u32>,
    pub hx_filter_key: u64,
    pub hx_table_keys: BootstrapTableKeys,
}

// Derives bootstrap runtime keys from static PE data and a known bootstrap string.
pub fn derive_bootstrap_keys_from_input(
    static_data: &BootstrapStaticData,
    bootstrap_input: &str,
) -> Result<BootstrapDerivedKeys, Box<dyn std::error::Error>> {
    let mut password = encode_utf16le(bootstrap_input);
    password.extend_from_slice(&encode_utf16le(&static_data.warning));

    let verified = derive_verified_material(&password, &static_data.params_payload)?;
    let control_block =
        control_block_from_drip_bytes(&verified.drip_table, static_data.params.flags);

    let mut bootstrap_key = derive_key_block(&password, KEY_BLOCK_LEN, 0)?;
    xor_in_place(&mut bootstrap_key, &verified.secret);
    let mut params_key = derive_key_block(&static_data.params_payload, KEY_BLOCK_LEN, 1)?;
    xor_in_place(&mut params_key, &verified.secret);

    let unique_block =
        derive_unique_block(&static_data.unique_utf16le, &static_data.archive_unique_key)?;
    let hx_filter_key = BinaryReader::u64_le(&unique_block[..8]);
    let hx_table_keys = derive_table_keys(&bootstrap_key, &params_key, &unique_block)?;

    Ok(BootstrapDerivedKeys {
        params: static_data.params.clone(),
        control_block,
        hx_filter_key,
        hx_table_keys,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn magalumina_static_data() -> BootstrapStaticData {
        let params_payload = vec![
            0x01, 0x05, 0x06, 0x04, 0x07, 0x02, 0x00, 0x03, 0x03, 0x00, 0x04, 0x01, 0x05, 0x02,
            0x00, 0x01, 0x02, 0x00, 0x00, 0x03, 0x3f, 0x02,
        ];
        BootstrapStaticData {
            params: crate::crypto::exe_resource::parse_bootstrap_params(&params_payload).unwrap(),
            params_payload,
            warning: "Warning! Extracting this game data may infringe on author's rights."
                .to_owned(),
            unique_utf16le: encode_utf16le("{KrSzkNnHbnMtrMmRn}"),
            archive_unique_key: [0xCE, 0xEA, 0xAF, 0x2C, 0xEF, 0xBE, 0xAD, 0xDE],
        }
    }

    #[test]
    fn derives_magalumina_control_and_filter_keys() {
        let derived = derive_bootstrap_keys_from_input(
            &magalumina_static_data(),
            "マガルミナ/Copyright_Purple_software_All_Rights_Reserved.",
        )
        .unwrap();
        assert_eq!(
            &derived.control_block[..8],
            &[
                230609351, 251370991, 3015076410, 3793746295, 4135954146, 67512001, 2028286762,
                1522179638,
            ]
        );
        assert_eq!(derived.hx_filter_key, 2172929777402632173);
        assert_eq!(
            derived.hx_table_keys.default_key1,
            [
                162, 22, 253, 21, 71, 251, 179, 64, 16, 26, 44, 191, 79, 42, 58, 96, 197, 158, 173,
                50, 167, 50, 91, 42, 187, 178, 200, 229, 30, 20, 162, 209,
            ]
        );
        assert_eq!(
            derived.hx_table_keys.default_key2,
            [
                2, 6, 176, 145, 65, 45, 214, 17, 76, 96, 205, 42, 171, 221, 63, 166
            ]
        );
        assert_eq!(
            derived.hx_table_keys.alternate_key1,
            [
                197, 72, 99, 201, 189, 78, 120, 232, 186, 164, 238, 67, 53, 57, 173, 153, 96, 6,
                40, 201, 12, 252, 243, 100, 232, 215, 61, 238, 43, 26, 125, 50,
            ]
        );
        assert_eq!(
            derived.hx_table_keys.alternate_key2,
            [
                3, 214, 70, 255, 70, 102, 11, 187, 196, 209, 94, 119, 255, 209, 139, 2
            ]
        );
    }
}

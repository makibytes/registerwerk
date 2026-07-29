// Registerwerk Cairo contracts — token standards with eWpG-oriented controls on Starknet
//
// Modules:
//   erc20   — EwpgERC20 fungible security token (whitelist, freeze, pause, cap,
//             forced operations, registry handover)
//   erc3525 — EwpgERC3525 semi-fungible token (slot+value) for bonds and fund tranches

pub mod erc20;
pub mod erc3525;

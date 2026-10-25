Changes to behaviour said: 11 of 11.
Changes to code alone kept silent: 5 of 6.
Read-path changes, silent by design: 1 of 1.

| Mutation | Kind | What it does | skimit | What skimit said |
|---|---|---|---|---|
| `owner_delete_without_lookup_check` | behaviour | an owner is deleted without first checking it exists | **caught** | DELETE /api/owners/{ownerId}: no longer checks ClinicService.findOwnerById before it deletes Owner (in OwnerRestControllerV1.deleteOwner) |
| `get_pet_deletes` | behaviour | reading a pet deletes it | **caught** | GET /api/pets/{petId} now deletes Pet (1 → 2 ways in) |
| `update_pet_keeps_old_type` | behaviour | updating a pet no longer changes its type | **caught** | PUT /api/pets/{petId}: writes Pet no longer using Pet.setType, PetDto.getType and 1 more (in PetRestControllerV1.updatePet) |
| `save_pet_without_type_lookup` | behaviour | a pet is saved with the type as sent, not the one on record | **caught** | 3 ways in (POST /api/owners/{ownerId}/pets, …): writes Pet no longer using ClinicServiceImpl.findPetTypeById, Pet.getType and 2 more (in ClinicServiceImpl.savePet) |
| `delete_owner_deletes_pets` | behaviour | deleting an owner now deletes their pets | **caught** | DELETE /api/owners/{ownerId} now deletes Pet (1 → 2 ways in)<br>DELETE /api/owners/{ownerId}: new check before it deletes Owner, on Owner.getPets (in ClinicServiceImpl.deleteOwner) |
| `save_owner_twice` | behaviour | an owner is saved twice | **caught** | 2 ways in (POST /api/owners, PUT /api/owners/{ownerId}): writes Owner with different data: OwnerRepository.save now called twice, not once (in ClinicServiceImpl.saveOwner) |
| `visit_needs_existing_pet` | behaviour | a visit is refused for a pet that does not exist | **caught** | POST /api/owners/{ownerId}/pets/{petId}/visits: new check before it writes Visit, on ClinicService.findPetById (in OwnerRestControllerV1.addVisitToOwner) |
| `owner_update_drops_telephone` | behaviour | updating an owner no longer stores the telephone | **caught** | PUT /api/owners/{ownerId}: writes Owner no longer using Owner.setTelephone and OwnerFieldsDto.getTelephone (in OwnerRestControllerV1.updateOwner) |
| `jpa_save_always_merges` | behaviour | the JPA repository merges every owner instead of persisting new ones | **caught** | 2 ways in (POST /api/owners, PUT /api/owners/{ownerId}): the code that writes Owner changed (in JpaOwnerRepositoryImpl.save: no longer checks Owner.getId; no longer uses EntityManager.persist) |
| `fragment_delete_skips_remove` | behaviour | the Spring Data delete fragment no longer removes the managed visit | **caught** | DELETE /api/visits/{visitId}: the code that deletes Visit changed (in SpringDataVisitRepositoryImpl.delete: no longer checks EntityManager.contains; no longer uses EntityManager.remove) |
| `service_delete_pet_noop` | behaviour | deleting a pet does nothing | **caught** | DELETE /api/pets/{petId} no longer deletes Pet (1 → 0 ways in) |
| `rename_save_owner` | same | ClinicService.saveOwner renamed to storeOwner, callers updated | **silent** | No change to what any way in writes, deletes, sends or calls. |
| `alias_local` | same | the owner passed through a second local | **silent** | No change to what any way in writes, deletes, sends or calls. |
| `reformat_save_pet` | same | a comment and line breaks in savePet | **silent** | No change to what any way in writes, deletes, sends or calls. |
| `log_before_delete` | same | a line printed before an owner is deleted | **silent** | No change to what any way in writes, deletes, sends or calls. |
| `reorder_setters` | same | two independent setters swapped | **FALSE LINE** | PUT /api/owners/{ownerId}: writes Owner with different data: the same steps in another order (in OwnerRestControllerV1.updateOwner) |
| `move_service_impl` | same | ClinicServiceImpl moved to a subpackage | **silent** | No change to what any way in writes, deletes, sends or calls. |
| `list_owners_empty_is_ok` | read | an empty owner list is 200, not 404 | **silent** | No change to what any way in writes, deletes, sends or calls. |

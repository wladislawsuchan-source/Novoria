package de.walahi.smpcore.api.internal;
import de.walahi.smpcore.api.service.ProfessionApi;
import de.walahi.smpcore.services.ProfessionService;
import java.util.Objects;
final class DefaultProfessionApi implements ProfessionApi {
    private final ProfessionService service;
    DefaultProfessionApi(ProfessionService service) { this.service = Objects.requireNonNull(service, "service"); }
    @Override public boolean available() { return service.available(); }
}

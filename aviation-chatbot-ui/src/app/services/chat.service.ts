import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

interface ChatResponse {
  response: string;
  steps?: any[];
}

@Injectable({
  providedIn: 'root'
})
export class ChatService {

  private apiUrl = 'http://localhost:8080/api/chat';

  constructor(private http: HttpClient) { }

  sendMessage(message: string): Observable<string> {
    return this.http.post<ChatResponse>(this.apiUrl, { message }).pipe(
      map(response => response.response)
    );
  }
}

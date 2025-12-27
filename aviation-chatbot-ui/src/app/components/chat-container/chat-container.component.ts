import { Component, OnInit } from '@angular/core';
import { ChatService } from '../../services/chat.service';
import { Message } from '../../models/message.model';

@Component({
  selector: 'app-chat-container',
  templateUrl: './chat-container.component.html',
  styleUrls: ['./chat-container.component.scss']
})
export class ChatContainerComponent implements OnInit {

  messages: Message[] = [];
  isLoading = false;

  constructor(private chatService: ChatService) { }

  ngOnInit(): void {
    // Add initial welcome message
    this.messages.push({
      text: 'Hello! I am your Aviation Assistant. Ask me about flight statuses, airlines, or airports.',
      sender: 'bot',
      timestamp: new Date()
    });
  }

  onSendMessage(text: string): void {
    if (!text.trim()) return;

    // Add user message
    this.messages.push({
      text: text,
      sender: 'user',
      timestamp: new Date()
    });

    this.isLoading = true;

    // Call service
    this.chatService.sendMessage(text).subscribe(
      (response) => {
        this.messages.push({
          text: response,
          sender: 'bot',
          timestamp: new Date()
        });
        this.isLoading = false;
      },
      (error) => {
        console.error('Error sending message:', error);
        this.messages.push({
          text: 'Sorry, I encountered an error while processing your request.',
          sender: 'bot',
          timestamp: new Date()
        });
        this.isLoading = false;
      }
    );
  }
}

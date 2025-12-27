import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

import { AppComponent } from './app.component';
import { ChatContainerComponent } from './components/chat-container/chat-container.component';
import { MessageListComponent } from './components/message-list/message-list.component';
import { MessageBubbleComponent } from './components/message-bubble/message-bubble.component';
import { InputAreaComponent } from './components/input-area/input-area.component';

@NgModule({
  declarations: [
    AppComponent,
    ChatContainerComponent,
    MessageListComponent,
    MessageBubbleComponent,
    InputAreaComponent
  ],
  imports: [
    BrowserModule,
    HttpClientModule,
    FormsModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
